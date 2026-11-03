package br.com.gestaosoftware.control.controller;

import org.springframework.beans.factory.annotation.Value;
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
    private final String master;

    public TenantCompanyInvoicesController(JdbcTemplate db,
            @Value("${GESTAO_TENANT_BRANDING_MASTER:}") String master) {
        this.db = db;
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
                       c.billing_due_day, c.payment_method
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
        result.put("invoices", invoices);
        result.put("invoiceCount", invoices.size());
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
