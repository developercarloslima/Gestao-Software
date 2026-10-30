package br.com.gestaosoftware.control.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;

@Service
public class PlatformManagementService {
    private static final Set<String> COMPANY_STATUSES = Set.of("TRIAL", "ACTIVE", "SUSPENDED", "CANCELLED");
    private static final Set<String> PLATFORM_OWNERS = Set.of("carlos.lima", "pedro.henrique");
    private static final Set<String> INVOICE_STATUSES = Set.of("PENDING", "PAID", "OVERDUE", "CANCELLED");
    private static final Set<String> PAYMENT_METHODS = Set.of("PIX_AUTOMATICO", "DEBITO_AUTOMATICO", "BOLETO");
    private static final Set<String> FILE_PURPOSES = Set.of("LOGO", "VISUAL_IDENTITY_PDF", "SITE_SOURCE_ZIP");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100.00");
    private static final BigDecimal VPS_ALERT_PERCENT = new BigDecimal("80.00");
    private static final BigDecimal VPS_CRITICAL_PERCENT = new BigDecimal("90.00");

    private final JdbcTemplate jdbc;

    public PlatformManagementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 10 * * * *")
    @Transactional
    public void maintainPlatformBilling() {
        ensureInvoices(YearMonth.now());
        refreshOverdue();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> modules() {
        return jdbc.query("""
                SELECT code, name, description, active, sort_order
                  FROM platform_modules
                 WHERE active = TRUE
                 ORDER BY sort_order, name
                """, (rs, row) -> mapOf(
                "code", rs.getString("code"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "active", rs.getBoolean("active"),
                "sortOrder", rs.getInt("sort_order")
        ));
    }

    @Transactional
    public Map<String, Object> dashboard(YearMonth month) {
        YearMonth target = month == null ? YearMonth.now() : month;
        ensureInvoices(target);
        refreshOverdue();

        LocalDate reference = target.atDay(1);
        BigDecimal grossRevenue = decimal("""
                SELECT COALESCE(SUM(amount),0) FROM platform_company_invoices
                 WHERE reference_month = ? AND status = 'PAID'
                """, reference);
        BigDecimal billedRevenue = decimal("""
                SELECT COALESCE(SUM(amount),0) FROM platform_company_invoices
                 WHERE reference_month = ? AND status <> 'CANCELLED'
                """, reference);
        BigDecimal contractedMrr = decimal("""
                SELECT COALESCE(SUM(monthly_fee),0) FROM platform_companies
                 WHERE status IN ('ACTIVE','TRIAL')
                """);
        BigDecimal manualExpenses = decimal("""
                SELECT COALESCE(SUM(amount),0) FROM platform_operating_expenses
                 WHERE reference_month = ?
                """, reference);
        BigDecimal infrastructureCost = decimal("""
                SELECT COALESCE(SUM(monthly_cost),0) FROM platform_vps WHERE active = TRUE
                """);
        BigDecimal operationalCosts = manualExpenses.add(infrastructureCost);
        BigDecimal netRevenue = grossRevenue.subtract(operationalCosts);

        int totalCompanies = integer("SELECT COUNT(*) FROM platform_companies");
        int activeCompanies = integer("SELECT COUNT(*) FROM platform_companies WHERE status IN ('ACTIVE','TRIAL')");
        int delinquentCompanies = integer("""
                SELECT COUNT(DISTINCT company_id) FROM platform_company_invoices WHERE status='OVERDUE'
                """);

        List<Map<String, Object>> partners = partnerAllocations(netRevenue);
        List<Map<String, Object>> delinquent = delinquentCompanies();
        List<Map<String, Object>> vps = listVps();
        Map<String, Object> capacity = capacitySummary(vps, activeCompanies);

        return mapOf(
                "referenceMonth", reference,
                "grossRevenue", grossRevenue,
                "billedRevenue", billedRevenue,
                "contractedMrr", contractedMrr,
                "manualOperatingExpenses", manualExpenses,
                "infrastructureCost", infrastructureCost,
                "operationalCosts", operationalCosts,
                "netOperationalRevenue", netRevenue,
                "totalCompanies", totalCompanies,
                "activeCompanies", activeCompanies,
                "delinquentCompaniesCount", delinquentCompanies,
                "partners", partners,
                "delinquentCompanies", delinquent,
                "vps", vps,
                "capacity", capacity
        );
    }

    @Transactional
    public List<Map<String, Object>> listCompanies() {
        refreshOverdue();
        return jdbc.query("""
                SELECT c.id, c.group_name, c.legal_name, c.trade_name, c.document, c.contact_name,
                       c.contact_email, c.contact_phone, c.tenant_admin_username, c.status, c.plan_code, c.plan_name, c.contract_term, c.monthly_fee,
                       c.billing_due_day, c.contract_start_date, c.contract_end_date, c.payment_method, c.created_at, c.updated_at,
                       c.platform_display_name, c.platform_legal_name, c.logo_url, c.favicon_url,
                       c.primary_color, c.secondary_color, c.accent_color, c.site_title, c.company_tagline,
                       c.login_subtitle, c.default_module_landing,
                       (SELECT COUNT(*) FROM platform_company_modules pcm WHERE pcm.company_id=c.id AND pcm.enabled=TRUE) AS module_count,
                       (SELECT COUNT(*) FROM platform_company_invoices i WHERE i.company_id=c.id AND i.status='OVERDUE') AS overdue_count,
                       h.vps_id, v.name AS vps_name, v.provider AS vps_provider, v.plan_name AS vps_plan,
                       (SELECT COUNT(*) FROM platform_companies gc
                          JOIN platform_company_modules gm ON gm.company_id=gc.id AND gm.module_code='APONTAMENTO_DIGITAL' AND gm.enabled=TRUE
                         WHERE c.group_name IS NOT NULL AND BTRIM(c.group_name)<>''
                           AND LOWER(BTRIM(gc.group_name))=LOWER(BTRIM(c.group_name))
                           AND gc.status IN ('ACTIVE','TRIAL')) AS group_point_companies
                  FROM platform_companies c
                  LEFT JOIN platform_company_hosting h ON h.company_id=c.id
                  LEFT JOIN platform_vps v ON v.id=h.vps_id
                 ORDER BY CASE c.status WHEN 'ACTIVE' THEN 0 WHEN 'TRIAL' THEN 1 WHEN 'SUSPENDED' THEN 2 ELSE 3 END,
                          c.trade_name
                """, (rs, row) -> companySummary(rs));
    }

    @Transactional
    public Map<String, Object> getCompany(UUID id) {
        refreshOverdue();
        Map<String, Object> company = jdbc.query("""
                SELECT c.*,
                       (SELECT COUNT(*) FROM platform_companies gc
                          JOIN platform_company_modules gm ON gm.company_id=gc.id AND gm.module_code='APONTAMENTO_DIGITAL' AND gm.enabled=TRUE
                         WHERE c.group_name IS NOT NULL AND BTRIM(c.group_name)<>''
                           AND LOWER(BTRIM(gc.group_name))=LOWER(BTRIM(c.group_name))
                           AND gc.status IN ('ACTIVE','TRIAL')) AS group_point_companies
                  FROM platform_companies c WHERE c.id=?
                """, rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Empresa-cliente não encontrada.");
            return companyDetail(rs);
        }, id);

        List<Map<String, Object>> enabledModules = jdbc.query("""
                SELECT m.code, m.name, m.description, pcm.enabled, pcm.monthly_price
                  FROM platform_modules m
                  LEFT JOIN platform_company_modules pcm ON pcm.module_code=m.code AND pcm.company_id=?
                 WHERE m.active=TRUE
                 ORDER BY m.sort_order, m.name
                """, (rs, row) -> mapOf(
                "code", rs.getString("code"),
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "enabled", rs.getBoolean("enabled"),
                "monthlyPrice", money(rs.getBigDecimal("monthly_price"))
        ), id);

        List<Map<String, Object>> invoices = jdbc.query("""
                SELECT id, reference_month, amount, due_date, status, paid_at, notes, created_at, updated_at
                  FROM platform_company_invoices
                 WHERE company_id=?
                 ORDER BY reference_month DESC
                 LIMIT 36
                """, (rs, row) -> invoiceMap(rs), id);

        Map<String, Object> hosting = jdbc.query("""
                SELECT h.company_id, h.vps_id, h.environment_name, h.domain, h.subdomain, h.dns_mode, h.app_url, h.api_url,
                       h.database_name, h.deployment_path, h.deployment_method, h.hosting_notes,
                       h.created_at, h.updated_at,
                       v.name AS vps_name, v.provider, v.plan_name, v.hostname, v.ip_address,
                       v.monthly_cost, v.max_companies, v.cpu_cores, v.ram_gb, v.storage_gb,
                       v.cpu_usage_percent, v.ram_usage_percent, v.storage_usage_percent, v.active AS vps_active
                  FROM platform_company_hosting h
                  LEFT JOIN platform_vps v ON v.id=h.vps_id
                 WHERE h.company_id=?
                """, rs -> rs.next() ? hostingMap(rs) : null, id);

        List<Map<String, Object>> files = jdbc.query("""
                SELECT id, purpose, original_name, content_type, file_size, created_at, updated_at
                  FROM platform_company_files
                 WHERE company_id=?
                 ORDER BY purpose, created_at DESC
                """, (rs, row) -> fileMetadataMap(rs), id);

        List<Map<String, Object>> contracts = jdbc.query("""
                SELECT id, company_id, contract_number, status, plan_code, plan_name, contract_term, monthly_fee, payment_method,
                       modules_snapshot, company_snapshot, client_sign_token, client_signed_by, client_signed_document, client_signed_at,
                       management_signed_by, management_signed_username, management_signed_at, effective_at, created_at, updated_at
                  FROM platform_company_contracts
                 WHERE company_id=?
                 ORDER BY created_at DESC
                """, (rs, row) -> contractMap(rs), id);

        company.put("modules", enabledModules);
        company.put("invoices", invoices);
        company.put("hosting", hosting);
        company.put("files", files);
        company.put("contracts", contracts);
        return company;
    }

    @Transactional
    public Map<String, Object> createCompany(CompanyInput input) {
        validateCompany(input);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_companies(
                    id, group_name, legal_name, trade_name, document, contact_name, contact_email, contact_phone, tenant_admin_username,
                    status, plan_code, plan_name, contract_term, monthly_fee, billing_due_day, contract_start_date, contract_end_date, payment_method,
                    platform_display_name, platform_legal_name, logo_url, favicon_url, primary_color, secondary_color, accent_color,
                    site_title, company_tagline, login_subtitle, default_module_landing,
                    notes, created_at, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())
                """,
                id, clean(input.groupName(), 160), required(input.legalName(), "Informe a razão social."),
                required(input.tradeName(), "Informe o nome da empresa."), cleanDocument(input.document()),
                clean(input.contactName(), 160), clean(input.contactEmail(), 180), clean(input.contactPhone(), 40),
                validatedTenantAdminUsername(input.tenantAdminUsername(), true), companyStatus(input.status()), planCode(input.planCode()), cleanOr(input.planName(), "Personalizado", 120), contractTerm(input.contractTerm()), money(input.monthlyFee()),
                dueDay(input.billingDueDay()), input.contractStartDate() == null ? LocalDate.now() : input.contractStartDate(),
                input.contractEndDate(), paymentMethod(input.paymentMethod()),
                cleanOr(input.platformDisplayName(), required(input.tradeName(), "Informe o nome da empresa."), 180),
                cleanOr(input.platformLegalName(), "Gestão Software LTDA", 180),
                clean(input.logoUrl(), 1000), clean(input.faviconUrl(), 1000),
                cleanOr(input.primaryColor(), "#0b5cff", 20), cleanOr(input.secondaryColor(), "#050b23", 20), cleanOr(input.accentColor(), "#e5f0ff", 20),
                cleanOr(input.siteTitle(), required(input.tradeName(), "Informe o nome da empresa."), 180),
                clean(input.companyTagline(), 220), clean(input.loginSubtitle(), 220), clean(input.defaultModuleLanding(), 80),
                clean(input.notes(), 10000)
        );
        replaceModules(id, input.modules());
        createOrRefreshPendingContract(id, true);
        ensureInvoices(YearMonth.now());
        return getCompany(id);
    }

    @Transactional
    public Map<String, Object> updateCompany(UUID id, CompanyInput input, String operator) {
        validateCompany(input);
        String storedStatus = jdbc.query("SELECT status FROM platform_companies WHERE id=? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (storedStatus == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
        String requestedStatus = companyStatus(input.status());
        String owner = operator == null ? "" : operator.trim().toLowerCase(Locale.ROOT);
        String transition = updateLifecycleTransition(storedStatus, requestedStatus, owner);
        if (!"NONE".equals(transition)) {
            // Mesma regra e auditoria dos botões da listagem. Toda a edição ocorre
            // numa transação: se salvar falhar, a transição também é revertida.
            if ("DEACTIVATE".equals(transition)) deactivateCompany(id, owner);
            else reactivateCompany(id, owner);
        }
        int changed = jdbc.update("""
                UPDATE platform_companies
                   SET group_name=?, legal_name=?, trade_name=?, document=?, contact_name=?, contact_email=?, contact_phone=?, tenant_admin_username=?,
                       status=?, plan_code=?, plan_name=?, contract_term=?, monthly_fee=?, billing_due_day=?, contract_start_date=?, contract_end_date=?, payment_method=?,
                       platform_display_name=?, platform_legal_name=?, logo_url=?, favicon_url=?,
                       primary_color=?, secondary_color=?, accent_color=?, site_title=?, company_tagline=?, login_subtitle=?,
                       default_module_landing=?, notes=?, updated_at=NOW()
                 WHERE id=?
                """,
                clean(input.groupName(), 160), required(input.legalName(), "Informe a razão social."),
                required(input.tradeName(), "Informe o nome da empresa."), cleanDocument(input.document()),
                clean(input.contactName(), 160), clean(input.contactEmail(), 180), clean(input.contactPhone(), 40),
                validatedTenantAdminUsername(input.tenantAdminUsername(), false), requestedStatus, planCode(input.planCode()), cleanOr(input.planName(), "Personalizado", 120), contractTerm(input.contractTerm()), money(input.monthlyFee()),
                dueDay(input.billingDueDay()), input.contractStartDate() == null ? LocalDate.now() : input.contractStartDate(),
                input.contractEndDate(), paymentMethod(input.paymentMethod()),
                cleanOr(input.platformDisplayName(), required(input.tradeName(), "Informe o nome da empresa."), 180),
                cleanOr(input.platformLegalName(), "Gestão Software LTDA", 180),
                clean(input.logoUrl(), 1000), clean(input.faviconUrl(), 1000),
                cleanOr(input.primaryColor(), "#0b5cff", 20), cleanOr(input.secondaryColor(), "#050b23", 20), cleanOr(input.accentColor(), "#e5f0ff", 20),
                cleanOr(input.siteTitle(), required(input.tradeName(), "Informe o nome da empresa."), 180), clean(input.companyTagline(), 220), clean(input.loginSubtitle(), 220),
                clean(input.defaultModuleLanding(), 80), clean(input.notes(), 10000), id
        );
        if (changed == 0) throw new IllegalArgumentException("Empresa-cliente não encontrada.");
        replaceModules(id, input.modules());
        createOrRefreshPendingContract(id, false);
        jdbc.update("""
                UPDATE platform_company_invoices
                   SET amount=?, updated_at=NOW()
                 WHERE company_id=? AND status='PENDING' AND reference_month>=?
                """, money(input.monthlyFee()), id, YearMonth.now().atDay(1));
        return getCompany(id);
    }

    /** Regra testável para edição do status, sem autorização implícita por payload. */
    static String updateLifecycleTransition(String current, String requested, String actor) {
        if (Objects.equals(current, requested)) return "NONE";
        if ("CANCELLED".equals(current) || "CANCELLED".equals(requested)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Para arquivar ou restaurar um cadastro excluído, utilize os botões próprios.");
        }
        if (!"SUSPENDED".equals(current) && !"SUSPENDED".equals(requested)) return "NONE";
        if (!PLATFORM_OWNERS.contains(actor == null ? "" : actor.trim().toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Somente Carlos Lima e Pedro Henrique podem desativar ou reativar empresas.");
        }
        return "SUSPENDED".equals(requested) ? "DEACTIVATE" : "REACTIVATE";
    }

    @Transactional
    public Map<String, Object> generateInvoice(UUID companyId, InvoiceInput input) {
        Map<String, Object> company = getCompany(companyId);
        LocalDate reference = firstOfMonth(input.referenceMonth() == null ? LocalDate.now() : input.referenceMonth());
        BigDecimal amount = input.amount() == null ? (BigDecimal) company.get("monthlyFee") : money(input.amount());
        LocalDate due = input.dueDate() == null
                ? YearMonth.from(reference).atDay((Integer) company.get("billingDueDay"))
                : input.dueDate();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_company_invoices(id, company_id, reference_month, amount, due_date, status, notes, created_at, updated_at)
                VALUES (?,?,?,?,?,'PENDING',?,NOW(),NOW())
                ON CONFLICT (company_id, reference_month) DO UPDATE SET
                    amount=EXCLUDED.amount, due_date=EXCLUDED.due_date, notes=EXCLUDED.notes, updated_at=NOW()
                """, id, companyId, reference, amount, due, clean(input.notes(), 600));
        refreshOverdue();
        return getCompany(companyId);
    }

    @Transactional
    public Map<String, Object> updateInvoice(UUID id, InvoiceUpdate input) {
        String status = input.status() == null ? null : input.status().trim().toUpperCase(Locale.ROOT);
        if (status != null && !INVOICE_STATUSES.contains(status)) throw new IllegalArgumentException("Status financeiro inválido.");
        Map<String, Object> existing = jdbc.query("""
                SELECT id, company_id, amount, due_date, status, paid_at, notes FROM platform_company_invoices WHERE id=?
                """, rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Mensalidade não encontrada.");
            return mapOf("companyId", rs.getObject("company_id", UUID.class), "amount", rs.getBigDecimal("amount"),
                    "dueDate", localDate(rs, "due_date"), "status", rs.getString("status"),
                    "paidAt", offset(rs, "paid_at"), "notes", rs.getString("notes"));
        }, id);
        BigDecimal amount = input.amount() == null ? (BigDecimal) existing.get("amount") : money(input.amount());
        LocalDate dueDate = input.dueDate() == null ? (LocalDate) existing.get("dueDate") : input.dueDate();
        String nextStatus = status == null ? (String) existing.get("status") : status;
        OffsetDateTime paidAt = "PAID".equals(nextStatus)
                ? (input.paidAt() == null ? (existing.get("paidAt") == null ? OffsetDateTime.now() : (OffsetDateTime) existing.get("paidAt")) : input.paidAt())
                : null;
        String notes = input.notes() == null ? (String) existing.get("notes") : clean(input.notes(), 600);
        jdbc.update("""
                UPDATE platform_company_invoices
                   SET amount=?, due_date=?, status=?, paid_at=?, notes=?, updated_at=NOW()
                 WHERE id=?
                """, amount, dueDate, nextStatus, paidAt, notes, id);
        refreshOverdue();
        return getCompany((UUID) existing.get("companyId"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listExpenses(YearMonth month) {
        LocalDate reference = (month == null ? YearMonth.now() : month).atDay(1);
        return jdbc.query("""
                SELECT id, reference_month, category, description, amount, notes, created_at, updated_at
                  FROM platform_operating_expenses WHERE reference_month=? ORDER BY created_at DESC
                """, (rs, row) -> expenseMap(rs), reference);
    }

    @Transactional
    public Map<String, Object> saveExpense(UUID id, ExpenseInput input) {
        LocalDate reference = firstOfMonth(input.referenceMonth() == null ? LocalDate.now() : input.referenceMonth());
        String category = required(input.category(), "Informe a categoria da despesa.");
        String description = required(input.description(), "Informe a descrição da despesa.");
        BigDecimal amount = money(input.amount());
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform_operating_expenses(id, reference_month, category, description, amount, notes, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,NOW(),NOW())
                    """, id, reference, category, description, amount, clean(input.notes(), 600));
        } else {
            int changed = jdbc.update("""
                    UPDATE platform_operating_expenses SET reference_month=?, category=?, description=?, amount=?, notes=?, updated_at=NOW() WHERE id=?
                    """, reference, category, description, amount, clean(input.notes(), 600), id);
            if (changed == 0) throw new IllegalArgumentException("Despesa não encontrada.");
        }
        UUID finalId = id;
        return jdbc.query("""
                SELECT id, reference_month, category, description, amount, notes, created_at, updated_at
                  FROM platform_operating_expenses WHERE id=?
                """, rs -> { if (!rs.next()) throw new IllegalArgumentException("Despesa não encontrada."); return expenseMap(rs); }, finalId);
    }

    @Transactional
    public void deleteExpense(UUID id) {
        if (jdbc.update("DELETE FROM platform_operating_expenses WHERE id=?", id) == 0) {
            throw new IllegalArgumentException("Despesa não encontrada.");
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> partners() {
        return partnerAllocations(BigDecimal.ZERO);
    }

    @Transactional
    public List<Map<String, Object>> updatePartnerShares(BigDecimal pedro, BigDecimal carlos) {
        BigDecimal p = percentage(pedro);
        BigDecimal c = percentage(carlos);
        if (p.add(c).compareTo(ONE_HUNDRED) != 0) {
            throw new IllegalArgumentException("A participação de Pedro Henrique + Carlos Lima deve totalizar 100%.");
        }
        jdbc.update("UPDATE platform_partners SET profit_share_percent=?, updated_at=NOW() WHERE code='PEDRO_HENRIQUE'", p);
        jdbc.update("UPDATE platform_partners SET profit_share_percent=?, updated_at=NOW() WHERE code='CARLOS_LIMA'", c);
        return partners();
    }

    @Transactional
    public List<Map<String, Object>> listVps() {
        return jdbc.query("""
                SELECT v.*,
                       (SELECT COUNT(*) FROM platform_company_hosting h
                         JOIN platform_companies c ON c.id=h.company_id
                        WHERE h.vps_id=v.id AND c.status IN ('ACTIVE','TRIAL')) AS allocated_companies
                  FROM platform_vps v
                 ORDER BY v.active DESC, v.name
                """, (rs, row) -> vpsMap(rs));
    }

    @Transactional
    public Map<String, Object> saveVps(UUID id, VpsInput input) {
        String name = required(input.name(), "Informe o nome da VPS.");
        String provider = required(input.provider(), "Informe o provedor da VPS.");
        BigDecimal monthlyCost = money(input.monthlyCost());
        int maxCompanies = input.maxCompanies() == null ? 10 : input.maxCompanies();
        if (maxCompanies <= 0) throw new IllegalArgumentException("A capacidade de empresas da VPS deve ser maior que zero.");
        validateUsage(input.cpuUsagePercent(), "CPU");
        validateUsage(input.ramUsagePercent(), "RAM");
        validateUsage(input.storageUsagePercent(), "armazenamento");
        boolean active = input.active() == null || input.active();
        OffsetDateTime metricsAt = (input.cpuUsagePercent()!=null || input.ramUsagePercent()!=null || input.storageUsagePercent()!=null)
                ? OffsetDateTime.now() : null;
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform_vps(id,name,provider,plan_name,hostname,ip_address,monthly_cost,max_companies,cpu_cores,ram_gb,storage_gb,
                                             cpu_usage_percent,ram_usage_percent,storage_usage_percent,active,renewal_date,last_metrics_at,notes,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())
                    """, id, name, provider, clean(input.planName(),160), clean(input.hostname(),200), clean(input.ipAddress(),80),
                    monthlyCost, maxCompanies, nonNegative(input.cpuCores(), "CPU"), nonNegative(input.ramGb(), "RAM"), nonNegative(input.storageGb(), "armazenamento"),
                    input.cpuUsagePercent(), input.ramUsagePercent(), input.storageUsagePercent(), active, input.renewalDate(), metricsAt, clean(input.notes(),10000));
        } else {
            int changed = jdbc.update("""
                    UPDATE platform_vps SET name=?, provider=?, plan_name=?, hostname=?, ip_address=?, monthly_cost=?, max_companies=?,
                           cpu_cores=?, ram_gb=?, storage_gb=?, cpu_usage_percent=?, ram_usage_percent=?, storage_usage_percent=?, active=?, renewal_date=?,
                           last_metrics_at=CASE WHEN ? IS NULL THEN last_metrics_at ELSE ? END, notes=?, updated_at=NOW() WHERE id=?
                    """, name, provider, clean(input.planName(),160), clean(input.hostname(),200), clean(input.ipAddress(),80), monthlyCost, maxCompanies,
                    nonNegative(input.cpuCores(), "CPU"), nonNegative(input.ramGb(), "RAM"), nonNegative(input.storageGb(), "armazenamento"),
                    input.cpuUsagePercent(), input.ramUsagePercent(), input.storageUsagePercent(), active, input.renewalDate(), metricsAt, metricsAt, clean(input.notes(),10000), id);
            if (changed == 0) throw new IllegalArgumentException("VPS não encontrada.");
        }
        UUID finalId = id;
        return listVps().stream().filter(v -> finalId.equals(v.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> saveHosting(UUID companyId, HostingInput input) {
        integerRequired("SELECT COUNT(*) FROM platform_companies WHERE id=?", companyId, "Empresa-cliente não encontrada.");
        if (input.vpsId() != null) integerRequired("SELECT COUNT(*) FROM platform_vps WHERE id=?", input.vpsId(), "VPS não encontrada.");
        // Empresas reservadas pela Gestão Cloud só alteram domínio/servidor pela aba Gestão Cloud.
        // Impede que a edição legada rompa a identidade do tenant ou sobrescreva uma URL validada.
        List<Map<String,Object>> cloudReservation=jdbc.query("SELECT hostname,target_vps_id FROM gs_cloud_domains WHERE company_id=?",
                (rs,n)->Map.of("hostname",rs.getString(1),"vpsId",rs.getObject(2,UUID.class)),companyId);
        if(!cloudReservation.isEmpty()) {
            Map<String,Object> reserved=cloudReservation.get(0);
            if(!reserved.get("vpsId").equals(input.vpsId()) || !reserved.get("hostname").equals(input.subdomain()))
                throw new IllegalArgumentException("Este endereço pertence à Gestão Cloud. Altere o subdomínio e a VPS somente pela aba Gestão Cloud.");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gs_deployments WHERE company_id=? AND status='ACTIVE')",Boolean.class,companyId))) {
                List<Map<String,Object>> prior=jdbc.query("SELECT app_url,api_url FROM platform_company_hosting WHERE company_id=?",
                        (rs,n)->{Map<String,Object> row=new HashMap<>();row.put("appUrl",rs.getString(1));row.put("apiUrl",rs.getString(2));return row;},companyId);
                if(!prior.isEmpty() && (!java.util.Objects.equals(prior.get(0).get("appUrl"),input.appUrl()) ||
                         !java.util.Objects.equals(prior.get(0).get("apiUrl"),input.apiUrl())))
                    throw new IllegalArgumentException("URL já validada. A alteração exige migração e nova verificação, não edição manual.");
            }
        }
        jdbc.update("""
                INSERT INTO platform_company_hosting(company_id,vps_id,environment_name,domain,subdomain,dns_mode,app_url,api_url,database_name,deployment_path,deployment_method,hosting_notes,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,NOW(),NOW())
                ON CONFLICT (company_id) DO UPDATE SET
                    vps_id=EXCLUDED.vps_id, environment_name=EXCLUDED.environment_name, domain=EXCLUDED.domain, subdomain=EXCLUDED.subdomain, dns_mode=EXCLUDED.dns_mode,
                    app_url=EXCLUDED.app_url, api_url=EXCLUDED.api_url, database_name=EXCLUDED.database_name,
                    deployment_path=EXCLUDED.deployment_path, deployment_method=EXCLUDED.deployment_method,
                    hosting_notes=EXCLUDED.hosting_notes, updated_at=NOW()
                """, companyId, input.vpsId(), clean(input.environmentName(),120), clean(input.domain(),240), clean(input.subdomain(),240), cleanOr(input.dnsMode(), "SUBDOMAIN", 30), clean(input.appUrl(),500),
                clean(input.apiUrl(),500), clean(input.databaseName(),160), clean(input.deploymentPath(),500), clean(input.deploymentMethod(),120),
                clean(input.hostingNotes(),10000));
        return getCompany(companyId);
    }

    // Ações administrativas são sempre auditadas; a 'exclusão' do painel é um
    // arquivamento lógico. Não apagar contratos, faturas, domínios, deploys ou
    // dados de clientes mantidos em bancos de tenants externos.
    private Map<String, String> lockedCompanyLifecycle(UUID companyId) {
        return jdbc.query("SELECT trade_name, status FROM platform_companies WHERE id=? FOR UPDATE",
                rs -> rs.next() ? Map.of("name", rs.getString(1), "status", rs.getString(2)) : null, companyId);
    }

    private void auditCompanyLifecycle(UUID companyId, String actor, String action, String detail) {
        jdbc.update("""
                INSERT INTO gs_cloud_audit(id,company_id,action,operator,message,created_at)
                VALUES(?,?,?,?,?,NOW())
                """, UUID.randomUUID(), companyId, action, actor, detail);
    }

    @Transactional
    public Map<String, Object> deactivateCompany(UUID companyId, String operator) {
        Map<String, String> company = lockedCompanyLifecycle(companyId);
        if (company == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
        String status = company.get("status");
        if ("CANCELLED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Empresa excluída do cadastro ativo.");
        if (!"SUSPENDED".equals(status)) {
            jdbc.update("""
                    UPDATE platform_companies SET status_before_suspend=status, status='SUSPENDED', updated_at=NOW() WHERE id=?
                    """, companyId);
            auditCompanyLifecycle(companyId, operator, "COMPANY_DEACTIVATED", "Empresa " + company.get("name") + " desativada (anterior: " + status + ").");
        }
        return getCompany(companyId);
    }

    @Transactional
    public Map<String, Object> reactivateCompany(UUID companyId, String operator) {
        Map<String, String> company = lockedCompanyLifecycle(companyId);
        if (company == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
        boolean archived = "CANCELLED".equals(company.get("status"));
        if (!"SUSPENDED".equals(company.get("status")) && !archived) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Somente empresas suspensas ou arquivadas podem ser reativadas.");
        }
        jdbc.update("""
                UPDATE platform_companies
                   SET status=COALESCE(status_before_suspend,'ACTIVE'), status_before_suspend=NULL, updated_at=NOW()
                 WHERE id=?
                """, companyId);
        auditCompanyLifecycle(companyId, operator, archived ? "COMPANY_RESTORED" : "COMPANY_REACTIVATED",
                "Empresa " + company.get("name") + (archived ? " restaurada do arquivo." : " reativada."));
        return getCompany(companyId);
    }

    @Transactional
    public void deleteCompany(UUID companyId, String operator) {
        Map<String, String> company = lockedCompanyLifecycle(companyId);
        if (company == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada.");
        if ("CANCELLED".equals(company.get("status"))) return;
        jdbc.update("""
                UPDATE platform_companies
                   SET status_before_suspend=CASE WHEN status IN ('ACTIVE','TRIAL') THEN status ELSE status_before_suspend END,
                       status='CANCELLED', updated_at=NOW()
                 WHERE id=?
                """, companyId);
        auditCompanyLifecycle(companyId, operator, "COMPANY_ARCHIVED", "Cadastro de " + company.get("name") + " excluído da lista ativa; contratos, faturas, domínios e deploys preservados.");
    }

    @Transactional
    public Map<String, Object> saveCompanyFile(UUID companyId, String purposeValue, String originalName, String contentType, byte[] content) {
        integerRequired("SELECT COUNT(*) FROM platform_companies WHERE id=?", companyId, "Empresa-cliente não encontrada.");
        String purpose = purposeValue == null ? "" : purposeValue.trim().toUpperCase(Locale.ROOT);
        if (!FILE_PURPOSES.contains(purpose)) throw new IllegalArgumentException("Tipo de arquivo inválido.");
        if (content == null || content.length == 0) throw new IllegalArgumentException("Arquivo vazio.");
        String type = contentType == null ? "application/octet-stream" : contentType.toLowerCase(Locale.ROOT);
        if ("LOGO".equals(purpose)) {
            if (!type.startsWith("image/")) throw new IllegalArgumentException("A logo precisa ser uma imagem.");
            if (content.length > 5 * 1024 * 1024) throw new IllegalArgumentException("A logo deve ter no máximo 5 MB.");
        } else if ("SITE_SOURCE_ZIP".equals(purpose)) {
            if (!String.valueOf(originalName).toLowerCase(Locale.ROOT).endsWith(".zip"))
                throw new IllegalArgumentException("O código do site precisa ser enviado em .zip.");
            if (content.length > 25 * 1024 * 1024)
                throw new IllegalArgumentException("O ZIP do site deve ter no máximo 25 MB.");
            if (content.length < 4 || content[0] != 'P' || content[1] != 'K')
                throw new IllegalArgumentException("Arquivo ZIP inválido.");
            validateSiteZipArchive(content);
            type = "application/zip";
        } else {
            if (!"application/pdf".equals(type)) throw new IllegalArgumentException("A identidade visual precisa ser enviada em PDF.");
            if (content.length > 20 * 1024 * 1024) throw new IllegalArgumentException("O PDF deve ter no máximo 20 MB.");
        }
        UUID fileId = jdbc.query("SELECT id FROM platform_company_files WHERE company_id=? AND purpose=?", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, companyId, purpose);
        if (fileId == null) {
            fileId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO platform_company_files(id, company_id, purpose, original_name, content_type, content, file_size, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,NOW(),NOW())
                    """, fileId, companyId, purpose, cleanOr(originalName, purpose.toLowerCase(Locale.ROOT), 255), type, content, content.length);
        } else {
            jdbc.update("""
                    UPDATE platform_company_files
                       SET original_name=?, content_type=?, content=?, file_size=?, updated_at=NOW()
                     WHERE id=?
                    """, cleanOr(originalName, purpose.toLowerCase(Locale.ROOT), 255), type, content, content.length, fileId);
        }
        if ("LOGO".equals(purpose)) {
            jdbc.update("UPDATE platform_companies SET logo_url=?, updated_at=NOW() WHERE id=?", "/api/public/platform/files/" + fileId, companyId);
        }
        return getCompany(companyId);
    }

    /**
     * Apenas armazena e valida o pacote; não extrai em diretórios web nem executa scripts.
     * Revisão/implantação em hospedagem separada é etapa humana posterior.
     */
    private static void validateSiteZipArchive(byte[] content) {
        long expanded = 0;
        int entries = 0;
        boolean hasFile = false;
        byte[] buffer = new byte[8192];
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(content))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2000) throw new IllegalArgumentException("ZIP com excesso de arquivos.");
                String name = entry.getName();
                if (name == null || name.startsWith("/") || name.startsWith("\\")
                        || name.indexOf('\\') >= 0 || name.indexOf((char)0) >= 0
                        || java.util.Arrays.asList(name.split("/")).contains(".."))
                    throw new IllegalArgumentException("ZIP contém caminhos inseguros.");
                if (!entry.isDirectory()) hasFile = true;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > 120L * 1024 * 1024)
                        throw new IllegalArgumentException("ZIP descompactado excede 120 MB.");
                }
                zip.closeEntry();
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("ZIP corrompido ou incompatível.", e);
        }
        if (!hasFile) throw new IllegalArgumentException("ZIP não contém arquivos de site.");
    }

    @Transactional(readOnly = true)
    public FilePayload getCompanyFile(UUID fileId, boolean publicOnly) {
        return jdbc.query("""
                SELECT id, company_id, purpose, original_name, content_type, content, file_size
                  FROM platform_company_files WHERE id=?
                """, rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Arquivo não encontrado.");
            String purpose = rs.getString("purpose");
            if (publicOnly && !"LOGO".equals(purpose)) throw new IllegalArgumentException("Arquivo não disponível publicamente.");
            return new FilePayload(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class), purpose,
                    rs.getString("original_name"), rs.getString("content_type"), rs.getBytes("content"), rs.getLong("file_size"));
        }, fileId);
    }

    @Transactional
    public Map<String, Object> signManagementContract(UUID companyId, UUID contractId, String username, String displayName) {
        if (!Set.of("carlos.lima", "pedro.henrique").contains(username == null ? "" : username.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Somente Carlos Lima ou Pedro Henrique podem assinar pela Gestão Software.");
        }
        integerRequired("SELECT COUNT(*) FROM platform_company_contracts WHERE id=? AND company_id=?", new Object[]{contractId, companyId}, "Contrato não encontrado.");
        String signerName = "carlos.lima".equalsIgnoreCase(username) ? "Carlos Lima" : "Pedro Henrique";
        jdbc.update("""
                UPDATE platform_company_contracts
                   SET management_signed_by=?, management_signed_username=?, management_signed_at=NOW(), updated_at=NOW()
                 WHERE id=? AND company_id=? AND status <> 'CANCELLED'
                """, signerName, username, contractId, companyId);
        refreshContractStatus(contractId);
        return getCompany(companyId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicContract(UUID token) {
        return jdbc.query("""
                SELECT pc.id, pc.company_id, pc.contract_number, pc.status, pc.plan_code, pc.plan_name, pc.contract_term,
                       pc.monthly_fee, pc.payment_method, pc.modules_snapshot, pc.company_snapshot, pc.client_sign_token,
                       pc.client_signed_by, pc.client_signed_document, pc.client_signed_at,
                       pc.management_signed_by, pc.management_signed_username, pc.management_signed_at, pc.effective_at,
                       pc.created_at, pc.updated_at, c.legal_name, c.trade_name, c.document, c.contact_name, c.contact_email
                  FROM platform_company_contracts pc
                  JOIN platform_companies c ON c.id=pc.company_id
                 WHERE pc.client_sign_token=?
                """, rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Contrato não encontrado.");
            Map<String,Object> result = contractMap(rs);
            result.put("legalName", rs.getString("legal_name"));
            result.put("tradeName", rs.getString("trade_name"));
            result.put("document", rs.getString("document"));
            result.put("contactName", rs.getString("contact_name"));
            result.put("contactEmail", rs.getString("contact_email"));
            return result;
        }, token);
    }

    @Transactional
    public Map<String, Object> signClientContract(UUID token, ClientContractSignature input) {
        if (input == null || !Boolean.TRUE.equals(input.accepted())) throw new IllegalArgumentException("É necessário aceitar o contrato para assinar.");
        String signer = required(input.signerName(), "Informe o nome de quem assina pela empresa.");
        String document = cleanDocument(input.signerDocument());
        UUID contractId = jdbc.query("SELECT id FROM platform_company_contracts WHERE client_sign_token=? AND status <> 'CANCELLED'", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, token);
        if (contractId == null) throw new IllegalArgumentException("Contrato não encontrado ou cancelado.");
        jdbc.update("""
                UPDATE platform_company_contracts
                   SET client_signed_by=?, client_signed_document=?, client_signed_at=NOW(), updated_at=NOW()
                 WHERE id=?
                """, clean(signer,180), document, contractId);
        refreshContractStatus(contractId);
        return publicContract(token);
    }

    private void createOrRefreshPendingContract(UUID companyId, boolean forceCreate) {
        Map<String,Object> c = jdbc.query("""
                SELECT id, legal_name, trade_name, document, contact_name, contact_email, plan_code, plan_name, contract_term, monthly_fee, payment_method
                  FROM platform_companies WHERE id=?
                """, rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Empresa-cliente não encontrada.");
            return mapOf("legalName", rs.getString("legal_name"), "tradeName", rs.getString("trade_name"), "document", rs.getString("document"),
                    "contactName", rs.getString("contact_name"), "contactEmail", rs.getString("contact_email"), "planCode", rs.getString("plan_code"),
                    "planName", rs.getString("plan_name"), "contractTerm", rs.getString("contract_term"), "monthlyFee", money(rs.getBigDecimal("monthly_fee")),
                    "paymentMethod", rs.getString("payment_method"));
        }, companyId);
        List<String> moduleNames = jdbc.queryForList("""
                SELECT m.name FROM platform_company_modules pcm
                JOIN platform_modules m ON m.code=pcm.module_code
                WHERE pcm.company_id=? AND pcm.enabled=TRUE ORDER BY m.sort_order, m.name
                """, String.class, companyId);
        String modulesSnapshot = moduleNames.isEmpty() ? "Nenhum módulo habilitado" : String.join("; ", moduleNames);
        String companySnapshot = "Razão social: " + Objects.toString(c.get("legalName"), "") +
                " | Nome fantasia: " + Objects.toString(c.get("tradeName"), "") +
                " | CPF/CNPJ: " + Objects.toString(c.get("document"), "") +
                " | Contato: " + Objects.toString(c.get("contactName"), "") +
                " | E-mail: " + Objects.toString(c.get("contactEmail"), "");

        Map<String,Object> latest = jdbc.query("""
                SELECT id, status, plan_code, plan_name, contract_term, monthly_fee, payment_method, modules_snapshot, company_snapshot
                  FROM platform_company_contracts WHERE company_id=? ORDER BY created_at DESC LIMIT 1
                """, rs -> rs.next() ? mapOf("id", rs.getObject("id",UUID.class), "status", rs.getString("status"), "planCode",rs.getString("plan_code"),
                "planName",rs.getString("plan_name"),"contractTerm",rs.getString("contract_term"),"monthlyFee",money(rs.getBigDecimal("monthly_fee")),
                "paymentMethod",rs.getString("payment_method"),"modulesSnapshot",rs.getString("modules_snapshot"),"companySnapshot",rs.getString("company_snapshot")) : null, companyId);
        boolean changed = latest == null || !Objects.equals(latest.get("planCode"), c.get("planCode")) || !Objects.equals(latest.get("planName"), c.get("planName")) ||
                !Objects.equals(latest.get("contractTerm"), c.get("contractTerm")) || !Objects.equals(latest.get("monthlyFee"), c.get("monthlyFee")) ||
                !Objects.equals(latest.get("paymentMethod"), c.get("paymentMethod")) || !Objects.equals(latest.get("modulesSnapshot"), modulesSnapshot) ||
                !Objects.equals(latest.get("companySnapshot"), companySnapshot);
        if (!forceCreate && !changed) return;
        if (!forceCreate && latest != null && !"SIGNED".equals(latest.get("status")) && !"CANCELLED".equals(latest.get("status"))) {
            jdbc.update("""
                    UPDATE platform_company_contracts
                       SET plan_code=?, plan_name=?, contract_term=?, monthly_fee=?, payment_method=?, modules_snapshot=?, company_snapshot=?,
                           status='PENDING_SIGNATURES', client_signed_by=NULL, client_signed_document=NULL, client_signed_at=NULL,
                           management_signed_by=NULL, management_signed_username=NULL, management_signed_at=NULL, effective_at=NULL,
                           client_sign_token=gen_random_uuid(), updated_at=NOW()
                     WHERE id=?
                    """, c.get("planCode"), c.get("planName"), c.get("contractTerm"), c.get("monthlyFee"), c.get("paymentMethod"),
                    modulesSnapshot, companySnapshot, latest.get("id"));
            return;
        }
        UUID contractId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        String contractNumber = "GS-" + LocalDate.now().getYear() + "-" + contractId.toString().substring(0,8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO platform_company_contracts(id, company_id, contract_number, status, plan_code, plan_name, contract_term, monthly_fee,
                    payment_method, modules_snapshot, company_snapshot, client_sign_token, created_at, updated_at)
                VALUES (?,?,?,'PENDING_SIGNATURES',?,?,?,?,?,?,?,?,NOW(),NOW())
                """, contractId, companyId, contractNumber, c.get("planCode"), c.get("planName"), c.get("contractTerm"), c.get("monthlyFee"),
                c.get("paymentMethod"), modulesSnapshot, companySnapshot, token);
    }

    private void refreshContractStatus(UUID contractId) {
        jdbc.update("""
                UPDATE platform_company_contracts
                   SET status = CASE
                         WHEN client_signed_at IS NOT NULL AND management_signed_at IS NOT NULL THEN 'SIGNED'
                         WHEN client_signed_at IS NOT NULL OR management_signed_at IS NOT NULL THEN 'PARTIALLY_SIGNED'
                         ELSE 'PENDING_SIGNATURES'
                       END,
                       effective_at = CASE WHEN client_signed_at IS NOT NULL AND management_signed_at IS NOT NULL THEN COALESCE(effective_at,NOW()) ELSE NULL END,
                       updated_at=NOW()
                 WHERE id=? AND status <> 'CANCELLED'
                """, contractId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> publicBranding(String host) {
        String normalized = normalizeHost(host);
        Map<String, Object> result = jdbc.query("""
                SELECT c.id, c.trade_name, c.status, c.platform_display_name, c.platform_legal_name, c.logo_url, c.favicon_url,
                       c.primary_color, c.secondary_color, c.accent_color, c.site_title, c.company_tagline, c.login_subtitle, c.default_module_landing,
                       h.domain, h.subdomain
                  FROM platform_companies c
                  LEFT JOIN platform_company_hosting h ON h.company_id=c.id
                 WHERE LOWER(COALESCE(h.domain,'')) = ? OR LOWER(COALESCE(h.subdomain,'')) = ?
                 ORDER BY c.updated_at DESC
                 LIMIT 1
                """, rs -> {
            if (!rs.next()) return null;
            return mapOf(
                    "companyId", rs.getObject("id", UUID.class),
                    "companyName", rs.getString("trade_name"),
                    "status", rs.getString("status"),
                    "platformDisplayName", firstNonBlank(rs.getString("platform_display_name"), rs.getString("trade_name")),
                    "platformLegalName", firstNonBlank(rs.getString("platform_legal_name"), "Gestão Software LTDA"),
                    "logoUrl", firstNonBlank(rs.getString("logo_url"), "/assets/logo-gestao-software.png"),
                    "faviconUrl", firstNonBlank(rs.getString("favicon_url"), "/assets/logo-gestao-software.png"),
                    "primaryColor", firstNonBlank(rs.getString("primary_color"), "#0b5cff"),
                    "secondaryColor", firstNonBlank(rs.getString("secondary_color"), "#050b23"),
                    "accentColor", firstNonBlank(rs.getString("accent_color"), "#e5f0ff"),
                    "siteTitle", firstNonBlank(rs.getString("site_title"), rs.getString("trade_name")),
                    "companyTagline", rs.getString("company_tagline"),
                    "loginSubtitle", rs.getString("login_subtitle"),
                    "defaultModuleLanding", rs.getString("default_module_landing"),
                    "domain", rs.getString("domain"),
                    "subdomain", rs.getString("subdomain")
            );
        }, normalized, normalized);

        if (result != null) {
            UUID companyId = (UUID) result.get("companyId");
            boolean active = "ACTIVE".equals(result.get("status")) || "TRIAL".equals(result.get("status"));
            List<String> enabledModules = active ? jdbc.queryForList("""
                    SELECT module_code FROM platform_company_modules
                     WHERE company_id=? AND enabled=TRUE
                     ORDER BY module_code
                    """, String.class, companyId) : List.of();
            result.put("enabledModules", enabledModules);
            result.put("active", active);
            return result;
        }
        Map<String, Object> fallback = mapOf(
                "companyId", null,
                "companyName", "Gestão Software",
                "platformDisplayName", "Gestão Software",
                "platformLegalName", "Gestão Software LTDA",
                "logoUrl", "/assets/logo-gestao-software.png",
                "faviconUrl", "/assets/logo-gestao-software.png",
                "primaryColor", "#1264f4",
                "secondaryColor", "#06164a",
                "accentColor", "#dcecff",
                "siteTitle", "Gestão Software",
                "companyTagline", "Tecnologia modular para empresas que querem crescer.",
                "loginSubtitle", "Control Plane e módulos white-label",
                "defaultModuleLanding", "/admin/"
        );
        fallback.put("enabledModules", List.of());
        fallback.put("active", true);
        return fallback;
    }

    private static String validatedTenantAdminUsername(String value, boolean required) {
        String username = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (username.isEmpty()) {
            if (required) throw new IllegalArgumentException("Informe o usuário administrador da empresa.");
            return null; // Empresas legadas podem completar o cadastro posteriormente.
        }
        if (username.length() > 120 || !username.matches("[a-z0-9][a-z0-9._@-]{2,119}")
                || "admin@nh.local".equals(username))
            throw new IllegalArgumentException("Usuário administrador inválido. Use 3 a 120 caracteres: letras, números, ponto, hífen, @ ou sublinhado; não use o login legado.");
        return username;
    }

    private void validateCompany(CompanyInput input) {
        if (input == null) throw new IllegalArgumentException("Dados da empresa não informados.");
        required(input.legalName(), "Informe a razão social.");
        required(input.tradeName(), "Informe o nome da empresa.");
        validatedTenantAdminUsername(input.tenantAdminUsername(), false);
        money(input.monthlyFee());
        dueDay(input.billingDueDay());
        companyStatus(input.status());
        planCode(input.planCode());
        contractTerm(input.contractTerm());
        paymentMethod(input.paymentMethod());
        cleanOr(input.primaryColor(), "#0b5cff", 20);
        cleanOr(input.secondaryColor(), "#050b23", 20);
        cleanOr(input.accentColor(), "#e5f0ff", 20);
        if (input.contractStartDate()!=null && input.contractEndDate()!=null && input.contractEndDate().isBefore(input.contractStartDate())) {
            throw new IllegalArgumentException("A data final do contrato não pode ser anterior à data inicial.");
        }
    }

    private void replaceModules(UUID companyId, List<ModuleInput> modules) {
        jdbc.update("DELETE FROM platform_company_modules WHERE company_id=?", companyId);
        if (modules == null) return;
        Set<String> valid = new HashSet<>();
        jdbc.query("SELECT code FROM platform_modules WHERE active=TRUE", rs -> { valid.add(rs.getString(1)); });
        for (ModuleInput module : modules) {
            if (module == null || !module.enabled()) continue;
            String code = module.code() == null ? "" : module.code().trim().toUpperCase(Locale.ROOT);
            if (!valid.contains(code)) throw new IllegalArgumentException("Módulo inválido: " + code);
            jdbc.update("""
                    INSERT INTO platform_company_modules(company_id,module_code,enabled,monthly_price,created_at,updated_at)
                    VALUES (?,?,TRUE,?,NOW(),NOW())
                    """, companyId, code, money(module.monthlyPrice()));
        }
    }

    private void ensureInvoices(YearMonth month) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        List<Map<String, Object>> companies = jdbc.query("""
                SELECT id, monthly_fee, billing_due_day, contract_start_date, contract_end_date
                  FROM platform_companies
                 WHERE status IN ('ACTIVE','TRIAL')
                   AND monthly_fee > 0
                   AND contract_start_date <= ?
                   AND (contract_end_date IS NULL OR contract_end_date >= ?)
                """, (rs, row) -> mapOf(
                "id", rs.getObject("id", UUID.class), "monthlyFee", rs.getBigDecimal("monthly_fee"),
                "dueDay", rs.getInt("billing_due_day")
        ), last, first);
        for (Map<String, Object> company : companies) {
            UUID companyId = (UUID) company.get("id");
            int dueDay = (Integer) company.get("dueDay");
            LocalDate dueDate = month.atDay(dueDay);
            jdbc.update("""
                    INSERT INTO platform_company_invoices(id,company_id,reference_month,amount,due_date,status,created_at,updated_at)
                    VALUES (gen_random_uuid(),?,?,?,?,'PENDING',NOW(),NOW())
                    ON CONFLICT (company_id, reference_month) DO NOTHING
                    """, companyId, first, company.get("monthlyFee"), dueDate);
        }
    }

    private void refreshOverdue() {
        jdbc.update("""
                UPDATE platform_company_invoices SET status='OVERDUE', updated_at=NOW()
                 WHERE status='PENDING' AND due_date < CURRENT_DATE
                """);
        jdbc.update("""
                UPDATE platform_company_invoices SET status='PENDING', updated_at=NOW()
                 WHERE status='OVERDUE' AND due_date >= CURRENT_DATE
                """);
    }

    private List<Map<String, Object>> partnerAllocations(BigDecimal netRevenue) {
        return jdbc.query("""
                SELECT code, name, profit_share_percent, active, updated_at
                  FROM platform_partners WHERE active=TRUE
                 ORDER BY CASE code WHEN 'PEDRO_HENRIQUE' THEN 1 WHEN 'CARLOS_LIMA' THEN 2 ELSE 9 END, name
                """, (rs, row) -> {
            BigDecimal share = money(rs.getBigDecimal("profit_share_percent"));
            BigDecimal allocation = signedMoney(netRevenue.multiply(share).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP));
            return mapOf("code", rs.getString("code"), "name", rs.getString("name"), "profitSharePercent", share,
                    "estimatedAmount", allocation, "updatedAt", offset(rs, "updated_at"));
        });
    }

    private List<Map<String, Object>> delinquentCompanies() {
        return jdbc.query("""
                SELECT c.id, c.trade_name, c.legal_name, c.group_name,
                       MIN(i.due_date) AS oldest_due_date,
                       SUM(i.amount) AS overdue_amount,
                       COUNT(*) AS overdue_invoices
                  FROM platform_company_invoices i
                  JOIN platform_companies c ON c.id=i.company_id
                 WHERE i.status='OVERDUE'
                 GROUP BY c.id, c.trade_name, c.legal_name, c.group_name
                 ORDER BY oldest_due_date, c.trade_name
                """, (rs, row) -> {
            LocalDate oldest = localDate(rs, "oldest_due_date");
            long days = oldest == null ? 0 : Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(oldest, LocalDate.now()));
            return mapOf("id", rs.getObject("id", UUID.class), "tradeName", rs.getString("trade_name"),
                    "legalName", rs.getString("legal_name"), "groupName", rs.getString("group_name"),
                    "oldestDueDate", oldest, "overdueAmount", money(rs.getBigDecimal("overdue_amount")),
                    "overdueInvoices", rs.getInt("overdue_invoices"), "daysOverdue", days);
        });
    }

    private Map<String, Object> capacitySummary(List<Map<String, Object>> vpsList, int activeCompanies) {
        int totalCapacity = 0;
        int allocated = 0;
        BigDecimal maxResource = BigDecimal.ZERO;
        boolean criticalNode = false;
        for (Map<String, Object> node : vpsList) {
            if (!Boolean.TRUE.equals(node.get("active"))) continue;
            totalCapacity += (Integer) node.get("maxCompanies");
            allocated += ((Number) node.get("allocatedCompanies")).intValue();
            BigDecimal risk = (BigDecimal) node.get("riskPercent");
            if (risk.compareTo(maxResource) > 0) maxResource = risk;
            if (risk.compareTo(VPS_CRITICAL_PERCENT) >= 0) criticalNode = true;
        }
        int unassigned = Math.max(0, activeCompanies - allocated);
        int spareCapacity = Math.max(0, totalCapacity - allocated);
        BigDecimal companyUse = totalCapacity <= 0 ? (activeCompanies > 0 ? ONE_HUNDRED : BigDecimal.ZERO)
                : new BigDecimal(activeCompanies).multiply(ONE_HUNDRED).divide(new BigDecimal(totalCapacity), 2, RoundingMode.HALF_UP);
        boolean needMore = (activeCompanies > 0 && totalCapacity == 0) || activeCompanies > totalCapacity
                || companyUse.compareTo(VPS_ALERT_PERCENT) >= 0 || maxResource.compareTo(new BigDecimal("85.00")) >= 0 || criticalNode;
        String status = needMore ? "PLAN_NEW_VPS" : "CAPACITY_OK";
        String message;
        if (activeCompanies > 0 && totalCapacity == 0) message = "Cadastre uma VPS: existem empresas ativas sem capacidade registrada.";
        else if (activeCompanies > totalCapacity) message = "A quantidade de empresas ativas já ultrapassa a capacidade registrada. Adicione uma nova VPS.";
        else if (criticalNode) message = "Uma ou mais VPS atingiram nível crítico (90% ou mais). Recomenda-se adicionar capacidade agora.";
        else if (companyUse.compareTo(VPS_ALERT_PERCENT) >= 0 || maxResource.compareTo(new BigDecimal("85.00")) >= 0)
            message = "Capacidade em atenção. Planeje a próxima VPS antes de novas ativações.";
        else if (unassigned > 0 && spareCapacity >= unassigned) message = "Existem " + unassigned + " empresa(s) sem VPS vinculada, mas a infraestrutura cadastrada ainda possui capacidade para alocá-las.";
        else message = "Capacidade atual dentro da margem planejada.";
        return mapOf("status", status, "needMoreVps", needMore, "message", message,
                "activeVps", (int) vpsList.stream().filter(v -> Boolean.TRUE.equals(v.get("active"))).count(),
                "totalCapacityCompanies", totalCapacity, "allocatedCompanies", allocated, "plannedCompanies", activeCompanies, "unassignedCompanies", unassigned,
                "companyCapacityUsePercent", companyUse, "highestResourceUsePercent", maxResource,
                "alertThresholdPercent", VPS_ALERT_PERCENT, "criticalThresholdPercent", VPS_CRITICAL_PERCENT);
    }

    private Map<String, Object> companySummary(ResultSet rs) throws SQLException {
        int groupPoint = rs.getInt("group_point_companies");
        return mapOf("id", rs.getObject("id", UUID.class), "groupName", rs.getString("group_name"),
                "legalName", rs.getString("legal_name"), "tradeName", rs.getString("trade_name"),
                "document", rs.getString("document"), "contactName", rs.getString("contact_name"),
                "contactEmail", rs.getString("contact_email"), "contactPhone", rs.getString("contact_phone"),
                "tenantAdminUsername", rs.getString("tenant_admin_username"),
                "status", rs.getString("status"), "planCode", rs.getString("plan_code"), "planName", rs.getString("plan_name"), "contractTerm", rs.getString("contract_term"),
                "monthlyFee", money(rs.getBigDecimal("monthly_fee")), "billingDueDay", rs.getInt("billing_due_day"),
                "contractStartDate", localDate(rs,"contract_start_date"), "contractEndDate", localDate(rs,"contract_end_date"),
                "paymentMethod", rs.getString("payment_method"),
                "moduleCount", rs.getInt("module_count"), "overdueCount", rs.getInt("overdue_count"),
                "delinquent", rs.getInt("overdue_count") > 0, "vpsId", rs.getObject("vps_id", UUID.class),
                "vpsName", rs.getString("vps_name"), "vpsProvider", rs.getString("vps_provider"), "vpsPlan", rs.getString("vps_plan"),
                "groupPointCompanies", groupPoint, "pointSuperAdminEligible", groupPoint >= 2,
                "platformDisplayName", rs.getString("platform_display_name"), "platformLegalName", rs.getString("platform_legal_name"),
                "logoUrl", rs.getString("logo_url"), "faviconUrl", rs.getString("favicon_url"),
                "primaryColor", rs.getString("primary_color"), "secondaryColor", rs.getString("secondary_color"),
                "accentColor", rs.getString("accent_color"), "siteTitle", rs.getString("site_title"),
                "companyTagline", rs.getString("company_tagline"), "loginSubtitle", rs.getString("login_subtitle"),
                "defaultModuleLanding", rs.getString("default_module_landing"),
                "createdAt", offset(rs,"created_at"), "updatedAt", offset(rs,"updated_at"));
    }

    private Map<String, Object> companyDetail(ResultSet rs) throws SQLException {
        int groupPoint = rs.getInt("group_point_companies");
        return mapOf("id", rs.getObject("id", UUID.class), "groupName", rs.getString("group_name"),
                "legalName", rs.getString("legal_name"), "tradeName", rs.getString("trade_name"),
                "document", rs.getString("document"), "contactName", rs.getString("contact_name"),
                "contactEmail", rs.getString("contact_email"), "contactPhone", rs.getString("contact_phone"),
                "tenantAdminUsername", rs.getString("tenant_admin_username"),
                "status", rs.getString("status"), "planCode", rs.getString("plan_code"), "planName", rs.getString("plan_name"), "contractTerm", rs.getString("contract_term"),
                "monthlyFee", money(rs.getBigDecimal("monthly_fee")), "billingDueDay", rs.getInt("billing_due_day"),
                "contractStartDate", localDate(rs,"contract_start_date"), "contractEndDate", localDate(rs,"contract_end_date"),
                "paymentMethod", rs.getString("payment_method"),
                "notes", rs.getString("notes"), "groupPointCompanies", groupPoint, "pointSuperAdminEligible", groupPoint >= 2,
                "platformDisplayName", rs.getString("platform_display_name"), "platformLegalName", rs.getString("platform_legal_name"),
                "logoUrl", rs.getString("logo_url"), "faviconUrl", rs.getString("favicon_url"),
                "primaryColor", rs.getString("primary_color"), "secondaryColor", rs.getString("secondary_color"),
                "accentColor", rs.getString("accent_color"), "siteTitle", rs.getString("site_title"),
                "companyTagline", rs.getString("company_tagline"), "loginSubtitle", rs.getString("login_subtitle"),
                "defaultModuleLanding", rs.getString("default_module_landing"),
                "createdAt", offset(rs,"created_at"), "updatedAt", offset(rs,"updated_at"));
    }

    private Map<String, Object> fileMetadataMap(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String purpose = rs.getString("purpose");
        return mapOf("id", id, "purpose", purpose, "originalName", rs.getString("original_name"),
                "contentType", rs.getString("content_type"), "fileSize", rs.getLong("file_size"),
                "url", "LOGO".equals(purpose) ? "/api/public/platform/files/" + id : "/api/platform/files/" + id,
                "createdAt", offset(rs,"created_at"), "updatedAt", offset(rs,"updated_at"));
    }

    private Map<String, Object> contractMap(ResultSet rs) throws SQLException {
        UUID token = rs.getObject("client_sign_token", UUID.class);
        return mapOf("id", rs.getObject("id", UUID.class), "companyId", rs.getObject("company_id", UUID.class),
                "contractNumber", rs.getString("contract_number"), "status", rs.getString("status"),
                "planCode", rs.getString("plan_code"), "planName", rs.getString("plan_name"), "contractTerm", rs.getString("contract_term"),
                "monthlyFee", money(rs.getBigDecimal("monthly_fee")), "paymentMethod", rs.getString("payment_method"),
                "modulesSnapshot", rs.getString("modules_snapshot"), "companySnapshot", rs.getString("company_snapshot"),
                "clientSignToken", token, "signatureUrl", token == null ? null : "/contract/?token=" + token,
                "clientSignedBy", rs.getString("client_signed_by"), "clientSignedDocument", rs.getString("client_signed_document"),
                "clientSignedAt", offset(rs,"client_signed_at"), "managementSignedBy", rs.getString("management_signed_by"),
                "managementSignedUsername", rs.getString("management_signed_username"), "managementSignedAt", offset(rs,"management_signed_at"),
                "effectiveAt", offset(rs,"effective_at"), "createdAt", offset(rs,"created_at"), "updatedAt", offset(rs,"updated_at"));
    }

    private Map<String, Object> invoiceMap(ResultSet rs) throws SQLException {
        return mapOf("id", rs.getObject("id", UUID.class), "referenceMonth", localDate(rs,"reference_month"),
                "amount", money(rs.getBigDecimal("amount")), "dueDate", localDate(rs,"due_date"), "status", rs.getString("status"),
                "paidAt", offset(rs,"paid_at"), "notes", rs.getString("notes"), "createdAt", offset(rs,"created_at"), "updatedAt", offset(rs,"updated_at"));
    }

    private Map<String, Object> expenseMap(ResultSet rs) throws SQLException {
        return mapOf("id", rs.getObject("id", UUID.class), "referenceMonth", localDate(rs,"reference_month"),
                "category", rs.getString("category"), "description", rs.getString("description"), "amount", money(rs.getBigDecimal("amount")),
                "notes", rs.getString("notes"), "createdAt", offset(rs,"created_at"), "updatedAt", offset(rs,"updated_at"));
    }

    private Map<String, Object> vpsMap(ResultSet rs) throws SQLException {
        int allocated = rs.getInt("allocated_companies");
        int maxCompanies = rs.getInt("max_companies");
        BigDecimal companyUse = maxCompanies <= 0 ? ONE_HUNDRED : new BigDecimal(allocated).multiply(ONE_HUNDRED).divide(new BigDecimal(maxCompanies), 2, RoundingMode.HALF_UP);
        BigDecimal cpu = nullableDecimal(rs.getBigDecimal("cpu_usage_percent"));
        BigDecimal ram = nullableDecimal(rs.getBigDecimal("ram_usage_percent"));
        BigDecimal storage = nullableDecimal(rs.getBigDecimal("storage_usage_percent"));
        BigDecimal risk = max(companyUse, cpu, ram, storage);
        String capacityStatus = risk.compareTo(VPS_CRITICAL_PERCENT) >= 0 ? "CRITICAL" : risk.compareTo(VPS_ALERT_PERCENT) >= 0 ? "ATTENTION" : "OK";
        return mapOf("id", rs.getObject("id", UUID.class), "name", rs.getString("name"), "provider", rs.getString("provider"),
                "planName", rs.getString("plan_name"), "hostname", rs.getString("hostname"), "ipAddress", rs.getString("ip_address"),
                "monthlyCost", money(rs.getBigDecimal("monthly_cost")), "maxCompanies", maxCompanies, "allocatedCompanies", allocated,
                "companyUsePercent", companyUse, "cpuCores", nullableDecimal(rs.getBigDecimal("cpu_cores")), "ramGb", nullableDecimal(rs.getBigDecimal("ram_gb")),
                "storageGb", nullableDecimal(rs.getBigDecimal("storage_gb")), "cpuUsagePercent", cpu, "ramUsagePercent", ram,
                "storageUsagePercent", storage, "riskPercent", risk, "capacityStatus", capacityStatus, "active", rs.getBoolean("active"),
                "renewalDate", localDate(rs,"renewal_date"), "lastMetricsAt", offset(rs,"last_metrics_at"), "notes", rs.getString("notes"));
    }

    private Map<String, Object> hostingMap(ResultSet rs) throws SQLException {
        return mapOf("companyId", rs.getObject("company_id", UUID.class), "vpsId", rs.getObject("vps_id", UUID.class),
                "environmentName", rs.getString("environment_name"), "domain", rs.getString("domain"), "subdomain", rs.getString("subdomain"), "dnsMode", rs.getString("dns_mode"), "appUrl", rs.getString("app_url"),
                "apiUrl", rs.getString("api_url"), "databaseName", rs.getString("database_name"), "deploymentPath", rs.getString("deployment_path"),
                "deploymentMethod", rs.getString("deployment_method"), "hostingNotes", rs.getString("hosting_notes"),
                "vpsName", rs.getString("vps_name"), "provider", rs.getString("provider"), "planName", rs.getString("plan_name"),
                "hostname", rs.getString("hostname"), "ipAddress", rs.getString("ip_address"), "monthlyCost", money(rs.getBigDecimal("monthly_cost")),
                "maxCompanies", nullableInteger(rs,"max_companies"), "cpuCores", nullableDecimal(rs.getBigDecimal("cpu_cores")),
                "ramGb", nullableDecimal(rs.getBigDecimal("ram_gb")), "storageGb", nullableDecimal(rs.getBigDecimal("storage_gb")),
                "cpuUsagePercent", nullableDecimal(rs.getBigDecimal("cpu_usage_percent")), "ramUsagePercent", nullableDecimal(rs.getBigDecimal("ram_usage_percent")),
                "storageUsagePercent", nullableDecimal(rs.getBigDecimal("storage_usage_percent")), "vpsActive", nullableBoolean(rs,"vps_active"));
    }

    private BigDecimal decimal(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return money(value);
    }
    private int integer(String sql, Object... args) { Integer value=jdbc.queryForObject(sql,Integer.class,args); return value==null?0:value; }
    private void integerRequired(String sql, Object arg, String message) { if(integer(sql,arg)<=0) throw new IllegalArgumentException(message); }
    private void integerRequired(String sql, Object[] args, String message) { if(integer(sql,args)<=0) throw new IllegalArgumentException(message); }
    private BigDecimal money(BigDecimal value) {
        BigDecimal result = value == null ? BigDecimal.ZERO : value;
        if (result.compareTo(BigDecimal.ZERO) < 0) throw new IllegalArgumentException("Valores financeiros não podem ser negativos.");
        return result.setScale(2, RoundingMode.HALF_UP);
    }
    private BigDecimal signedMoney(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
    private BigDecimal percentage(BigDecimal value) {
        BigDecimal result = value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        if (result.compareTo(BigDecimal.ZERO)<0 || result.compareTo(ONE_HUNDRED)>0) throw new IllegalArgumentException("Percentual inválido.");
        return result;
    }
    private BigDecimal nonNegative(BigDecimal value, String label) {
        if (value == null) return null;
        if (value.compareTo(BigDecimal.ZERO)<0) throw new IllegalArgumentException(label + " não pode ser negativo.");
        return value;
    }
    private void validateUsage(BigDecimal value, String label) {
        if (value != null && (value.compareTo(BigDecimal.ZERO)<0 || value.compareTo(ONE_HUNDRED)>0)) throw new IllegalArgumentException("Uso de " + label + " deve ficar entre 0 e 100%.");
    }
    private int dueDay(Integer value) { int day=value==null?10:value; if(day<1||day>28) throw new IllegalArgumentException("O vencimento mensal deve ficar entre os dias 1 e 28."); return day; }
    private String companyStatus(String value) { String s=value==null||value.isBlank()?"ACTIVE":value.trim().toUpperCase(Locale.ROOT); if(!COMPANY_STATUSES.contains(s)) throw new IllegalArgumentException("Status da empresa inválido."); return s; }
    private String planCode(String value) { String s=value==null||value.isBlank()?"CUSTOM":value.trim().toUpperCase(Locale.ROOT); if(!Set.of("START","BUSINESS","PRO","SCALE","ENTERPRISE","DEDICATED","HYBRID","CUSTOM").contains(s)) throw new IllegalArgumentException("Plano inválido."); return s; }
    private String contractTerm(String value) { String s=value==null||value.isBlank()?"MONTHLY":value.trim().toUpperCase(Locale.ROOT); if(!Set.of("MONTHLY","SEMIANNUAL","ANNUAL").contains(s)) throw new IllegalArgumentException("Prazo contratual inválido."); return s; }
    private String paymentMethod(String value) { String s=value==null||value.isBlank()?"BOLETO":value.trim().toUpperCase(Locale.ROOT); if(!PAYMENT_METHODS.contains(s)) throw new IllegalArgumentException("Forma de pagamento inválida."); return s; }
    private String cleanDocument(String value) { if(value==null||value.isBlank()) return null; String digits=value.replaceAll("\\D",""); if(digits.isBlank()) return null; if(digits.length()!=11 && digits.length()!=14) throw new IllegalArgumentException("CPF/CNPJ deve ter 11 ou 14 dígitos."); return digits; }
    private String required(String value, String message) { if(value==null||value.isBlank()) throw new IllegalArgumentException(message); return value.trim().replaceAll("\\s+"," "); }
    private String clean(String value, int max) { if(value==null||value.isBlank()) return null; String v=value.trim().replaceAll("\\s+"," "); return v.substring(0,Math.min(max,v.length())); }
    private String cleanOr(String value,String fallback,int max){String c=clean(value,max);return c==null?fallback:c;}
    private LocalDate firstOfMonth(LocalDate value){return value.withDayOfMonth(1);}
    private LocalDate localDate(ResultSet rs,String column) throws SQLException { java.sql.Date d=rs.getDate(column); return d==null?null:d.toLocalDate(); }
    private OffsetDateTime offset(ResultSet rs,String column) throws SQLException { return rs.getObject(column, OffsetDateTime.class); }
    private Integer nullableInteger(ResultSet rs,String column)throws SQLException{int v=rs.getInt(column);return rs.wasNull()?null:v;}
    private Boolean nullableBoolean(ResultSet rs,String column)throws SQLException{boolean v=rs.getBoolean(column);return rs.wasNull()?null:v;}
    private BigDecimal nullableDecimal(BigDecimal value){return value==null?null:value.setScale(2,RoundingMode.HALF_UP);}
    private BigDecimal max(BigDecimal... values){BigDecimal m=BigDecimal.ZERO;for(BigDecimal v:values)if(v!=null&&v.compareTo(m)>0)m=v;return m.setScale(2,RoundingMode.HALF_UP);}
    private Map<String,Object> mapOf(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}

    private String normalizeHost(String host) {
        if (host == null || host.isBlank()) return "";
        String value = host.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        return value;
    }
    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    public record ModuleInput(String code, boolean enabled, BigDecimal monthlyPrice) {}
    public record CompanyInput(String groupName, String legalName, String tradeName, String document,
                               String contactName, String contactEmail, String contactPhone, String tenantAdminUsername, String status,
                               String planCode, String planName, String contractTerm, BigDecimal monthlyFee, Integer billingDueDay, String paymentMethod,
                               LocalDate contractStartDate, LocalDate contractEndDate,
                               String platformDisplayName, String platformLegalName, String logoUrl, String faviconUrl,
                               String primaryColor, String secondaryColor, String accentColor,
                               String siteTitle, String companyTagline, String loginSubtitle, String defaultModuleLanding,
                               String notes, List<ModuleInput> modules) {}
    public record InvoiceInput(LocalDate referenceMonth, BigDecimal amount, LocalDate dueDate, String notes) {}
    public record InvoiceUpdate(BigDecimal amount, LocalDate dueDate, String status, OffsetDateTime paidAt, String notes) {}
    public record ExpenseInput(LocalDate referenceMonth, String category, String description, BigDecimal amount, String notes) {}
    public record VpsInput(String name, String provider, String planName, String hostname, String ipAddress,
                           BigDecimal monthlyCost, Integer maxCompanies, BigDecimal cpuCores, BigDecimal ramGb,
                           BigDecimal storageGb, BigDecimal cpuUsagePercent, BigDecimal ramUsagePercent,
                           BigDecimal storageUsagePercent, Boolean active, LocalDate renewalDate, String notes) {}
    public record HostingInput(UUID vpsId, String environmentName, String domain, String subdomain, String dnsMode, String appUrl, String apiUrl,
                               String databaseName, String deploymentPath, String deploymentMethod, String hostingNotes) {}
    public record ClientContractSignature(String signerName, String signerDocument, Boolean accepted) {}
    public record FilePayload(UUID id, UUID companyId, String purpose, String originalName, String contentType, byte[] content, long fileSize) {}
}
