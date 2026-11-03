package br.com.gestaosoftware.control.controller;

import org.springframework.beans.factory.annotation.Value;
import br.com.gestaosoftware.control.service.ProvisioningService;
import br.com.gestaosoftware.control.service.PlatformBillingScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Apenas leitura das mensalidades SaaS da empresa vinculada ao host autenticado.
 * O token HMAC exclusivo da instalação não é enviado ao navegador.
 * Não cria cobranças e não concede acesso a outras empresas.
 */
@RestController
@RequestMapping("/api/public/platform/tenant-invoices")
public class TenantCompanyInvoicesController {
    private final JdbcTemplate db;
    private final ProvisioningService provisioning;
    private final PlatformBillingScheduleService billingSchedule;
    private final String master;

    public TenantCompanyInvoicesController(JdbcTemplate db, ProvisioningService provisioning, PlatformBillingScheduleService billingSchedule,
            @Value("${GESTAO_TENANT_BRANDING_MASTER:}") String master) {
        this.db = db;
        this.provisioning = provisioning;
        this.billingSchedule = billingSchedule;
        this.master = master;
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader("X-Gestao-Tenant-Host") String hostHeader,
                                    @RequestHeader("X-Gestao-Tenant-Token") String token) {
        String host = hostHeader == null ? "" : hostHeader.trim().toLowerCase(Locale.ROOT);
        if (!host.matches("[a-z0-9][a-z0-9.-]{2,250}") || host.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Host inválido.");
        }
        if (master == null || master.length() < 32) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Consulta de mensalidades não configurada.");
        }
        if (!authorized(master, host, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Empresa não autorizada.");
        }

        // Sem filtro de status: empresas suspensas também precisam consultar débitos
        // para negociar a reativação. A identidade continua garantida pelo HMAC do host.
        List<Map<String, Object>> companies = db.query("""
                SELECT c.id, c.trade_name, c.status, c.plan_name, c.monthly_fee,
                       c.billing_due_day, c.payment_method, c.next_renewal_date, c.renewal_choice,
                       c.contract_term, c.contract_start_date
                  FROM platform_companies c
                  JOIN platform_company_hosting h ON h.company_id = c.id
                 WHERE LOWER(COALESCE(h.domain, '')) = ?
                    OR LOWER(COALESCE(h.subdomain, '')) = ?
                 LIMIT 2
                """, (rs, row) -> {
            Map<String, Object> company = new LinkedHashMap<>();
            company.put("companyId", rs.getObject("id", UUID.class).toString());
            company.put("companyName", rs.getString("trade_name"));
            company.put("companyStatus", rs.getString("status"));
            company.put("planName", rs.getString("plan_name"));
            company.put("monthlyFee", rs.getBigDecimal("monthly_fee"));
            company.put("billingDueDay", rs.getInt("billing_due_day"));
            company.put("paymentMethod", rs.getString("payment_method"));
            company.put("renewalChoice", rs.getString("renewal_choice"));
            company.put("contractTerm", rs.getString("contract_term"));
            Date contractStart = rs.getDate("contract_start_date");
            Date renewalDate = rs.getDate("next_renewal_date");
            company.put("contractStartDate", contractStart == null ? null : contractStart.toLocalDate().toString());
            company.put("nextRenewalDate", renewalDate == null ? null : renewalDate.toLocalDate().toString());
            return company;
        }, host, host);
        if (companies.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não vinculada ao host.");
        }
        if (companies.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Host vinculado a mais de uma empresa.");
        }
        Map<String, Object> result = new LinkedHashMap<>(companies.get(0));
        UUID companyId = UUID.fromString((String) result.get("companyId"));
        List<Map<String, Object>> invoices = db.query("""
                SELECT i.id, i.reference_month, i.amount, i.due_date,
                       CASE WHEN i.status = 'PENDING' AND i.due_date < CURRENT_DATE
                            THEN 'OVERDUE' ELSE i.status END AS display_status,
                       i.paid_at, co.status AS checkout_status, co.invoice_url
                  FROM platform_company_invoices i
                  LEFT JOIN gs_invoice_checkout co ON co.invoice_id = i.id
                 WHERE i.company_id = ?
                 ORDER BY i.reference_month DESC, i.created_at DESC
                 LIMIT 36
                """, (rs, row) -> {
            Map<String, Object> invoice = new LinkedHashMap<>();
            invoice.put("id", rs.getObject("id", UUID.class).toString());
            Date reference = rs.getDate("reference_month");
            Date dueDate = rs.getDate("due_date");
            Timestamp paidAt = rs.getTimestamp("paid_at");
            invoice.put("referenceMonth", reference == null ? null : reference.toLocalDate().toString());
            invoice.put("amount", rs.getBigDecimal("amount"));
            invoice.put("dueDate", dueDate == null ? null : dueDate.toLocalDate().toString());
            invoice.put("status", rs.getString("display_status"));
            invoice.put("paidAt", paidAt == null ? null : paidAt.toInstant().toString());
            String link = rs.getString("invoice_url");
            invoice.put("invoiceUrl", "CREATED".equals(rs.getString("checkout_status"))
                    && verifiedInvoiceUrl(link) ? link : null);
            return invoice;
        }, companyId);
        List<Map<String, Object>> visibleInvoices = withExpectedMonthlyInstallments(result, invoices);
        result.put("invoices", visibleInvoices);
        result.put("invoiceCount", invoices.size()); // Somente faturas efetivamente registradas.
        result.put("displayedInstallmentCount", visibleInvoices.size());
        result.put("renewal", billingSchedule.status(companyId));
        BigDecimal overdue = BigDecimal.ZERO;
        int overdueCount = 0;
        for (Map<String, Object> invoice : invoices) {
            if ("OVERDUE".equals(invoice.get("status"))) {
                overdueCount++;
                overdue = overdue.add((BigDecimal) invoice.get("amount"));
            }
        }
        result.put("overdueCount", overdueCount);
        result.put("overdueAmount", overdue);
        return result;
    }

    /** Reconsulta títulos existentes no Asaas, sem emitir nem cobrar novamente. */
    @org.springframework.web.bind.annotation.PostMapping("/sync")
    public Map<String, Object> synchronize(@RequestHeader("X-Gestao-Tenant-Host") String host,
                                           @RequestHeader("X-Gestao-Tenant-Token") String token) {
        Map<String, Object> snapshot = list(host, token);
        UUID companyId = UUID.fromString((String) snapshot.get("companyId"));
        List<UUID> ids = db.query("""
                SELECT i.id FROM platform_company_invoices i
                JOIN gs_invoice_checkout c ON c.invoice_id=i.id
                WHERE i.company_id=? AND c.status='CREATED' AND c.provider_payment_id IS NOT NULL
                  AND i.status IN ('PENDING','OVERDUE','CONFIRMED','PAID','PAID_IN_CASH')
                ORDER BY CASE WHEN i.status IN ('PENDING','OVERDUE','CONFIRMED') THEN 0 ELSE 1 END,
                         i.reference_month DESC
                LIMIT 5
                """, (rs, row) -> rs.getObject(1, UUID.class), companyId);
        int checked = 0, changed = 0, failed = 0;
        for (UUID invoiceId : ids) {
            try {
                Map<String, Object> updated = provisioning.synchronizeAsaasInvoice(invoiceId);
                checked++;
                if (Boolean.TRUE.equals(updated.get("changed"))) changed++;
            } catch (RuntimeException ignored) {
                failed++;
            }
        }
        Map<String, Object> updated = new LinkedHashMap<>(list(host, token));
        updated.put("checked", checked);
        updated.put("changed", changed);
        updated.put("failed", failed);
        return updated;
    }

    /**
     * O tenant já verificou o papel ADMIN/BUYER antes de encaminhar o pedido.
     * Este endpoint exige novamente o HMAC de host e usa o companyId resolvido
     * no banco, jamais um identificador enviado pelo navegador.
     */
    @org.springframework.web.bind.annotation.PostMapping("/renewal")
    public Map<String,Object> renewal(
            @RequestHeader("X-Gestao-Tenant-Host") String host,
            @RequestHeader("X-Gestao-Tenant-Token") String token,
            @org.springframework.web.bind.annotation.RequestBody Map<String,String> choice) {
        Map<String,Object> company = list(host,token);
        UUID companyId=UUID.fromString((String)company.get("companyId"));
        billingSchedule.decide(companyId,choice==null?null:choice.get("action"));
        return list(host,token);
    }


    /**
     * Expõe as 12 competências do ciclo mensal sem inventar títulos financeiros.
     * Entradas SCHEDULED são apenas previsões de leitura: não têm id nem boleto.
     * Uma cobrança real sempre prevalece, inclusive quando já está paga.
     */
    static List<Map<String, Object>> withExpectedMonthlyInstallments(
            Map<String, Object> company, List<Map<String, Object>> actualInvoices) {
        if (!"MONTHLY".equals(company.get("contractTerm")) || company.get("contractStartDate") == null) {
            return actualInvoices;
        }
        LocalDate contractStart = LocalDate.parse((String) company.get("contractStartDate"));
        LocalDate cycleStart = contractStart;
        String renewalText = (String) company.get("nextRenewalDate");
        if (renewalText != null && !renewalText.isBlank()) {
            LocalDate renewal = LocalDate.parse(renewalText);
            if (renewal.isAfter(contractStart)) cycleStart = renewal.minusYears(1);
        }

        List<Map<String, Object>> rows = new ArrayList<>(actualInvoices);
        Set<YearMonth> recordedMonths = new HashSet<>();
        for (Map<String, Object> invoice : actualInvoices) {
            Object reference = invoice.get("referenceMonth");
            if (reference != null) recordedMonths.add(YearMonth.parse(reference.toString().substring(0, 7)));
        }
        int dueDay = Math.max(1, Math.min(31, ((Number) company.get("billingDueDay")).intValue()));
        for (int offset = 0; offset < 12; offset++) {
            YearMonth month = YearMonth.from(cycleStart.plusMonths(offset));
            if (recordedMonths.contains(month)) continue;
            LocalDate dueDate = month.atDay(Math.min(dueDay, month.lengthOfMonth()));
            if (offset == 0 && dueDate.isBefore(cycleStart)) dueDate = cycleStart;

            Map<String, Object> forecast = new LinkedHashMap<>();
            forecast.put("id", null);
            forecast.put("referenceMonth", month.atDay(1).toString());
            forecast.put("amount", company.get("monthlyFee"));
            forecast.put("dueDate", dueDate.toString());
            forecast.put("status", "SCHEDULED");
            forecast.put("paidAt", null);
            forecast.put("invoiceUrl", null);
            rows.add(forecast);
        }
        rows.sort(Comparator.comparing(
                (Map<String, Object> item) -> String.valueOf(item.get("referenceMonth"))).reversed());
        return rows;
    }

    static boolean authorized(String secret, String host, String token) {
        if (secret == null || secret.length() < 32 || host == null || token == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(host.getBytes(StandardCharsets.UTF_8));
            byte[] supplied = HexFormat.of().parseHex(token.trim().toLowerCase(Locale.ROOT));
            return MessageDigest.isEqual(expected, supplied);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean verifiedInvoiceUrl(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI uri = URI.create(raw);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && (uri.getUserInfo() == null);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
