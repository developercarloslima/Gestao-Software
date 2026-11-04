package br.com.gestaosoftware.control.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Emite imediatamente (depois do commit) as cobranças do ciclo, cada uma
 * com seu próprio vencimento futuro. Nunca migra ou duplica contratos legados.
 */
@Service
public class PlatformBillingScheduleService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private final JdbcTemplate db;
    private final PlatformPaymentGateway gateway;
    @org.springframework.beans.factory.annotation.Value("${app.financial.auto-issue-batches:false}")
    private boolean autoIssueBatches;

    public PlatformBillingScheduleService(JdbcTemplate db, PlatformPaymentGateway gateway) {
        this.db = db;
        this.gateway = gateway;
    }

    public static LocalDate today() { return LocalDate.now(BUSINESS_ZONE); }

    private record Plan(UUID id, LocalDate start, BigDecimal monthly, int day, String term,
                        LocalDate renewalDate, String choice, boolean enabled) {}

    private Plan find(UUID id, boolean lock) {
        String sql = """
            SELECT id, contract_start_date, monthly_fee, billing_due_day, contract_term,
                   next_renewal_date, renewal_choice, advance_billing_enabled
              FROM platform_companies WHERE id=?
            """ + (lock ? " FOR UPDATE" : "");
        return db.query(sql, rs -> rs.next() ? new Plan(
                rs.getObject("id",UUID.class), rs.getDate("contract_start_date").toLocalDate(),
                rs.getBigDecimal("monthly_fee"),rs.getInt("billing_due_day"),rs.getString("contract_term"),
                rs.getDate("next_renewal_date")==null?null:rs.getDate("next_renewal_date").toLocalDate(),
                rs.getString("renewal_choice"),rs.getBoolean("advance_billing_enabled")) : null,id);
    }

    /** Chamar na mesma transação que cria a empresa. */
    @Transactional
    public void start(UUID companyId) {
        Plan plan = find(companyId,true);
        if (plan==null || !plan.enabled()) return;
        LocalDate start = plan.start().isAfter(today()) ? plan.start() : today();
        generate(plan,start,true);
        db.update("UPDATE platform_companies SET next_renewal_date=?,renewal_choice='AUTO',updated_at=NOW() WHERE id=?",
                Date.valueOf(start.plusYears(1)),companyId);
    }


    /**
     * Se uma empresa nasceu com mensalidade zero, não houve emissão do ciclo.
     * Quando o valor é corrigido, completa apenas o primeiro ciclo ainda vigente;
     * ON CONFLICT preserva pagamentos e cobranças já existentes.
     * Não reabre ciclos antigos, não altera a renovação e não reenvia cobranças.
     */
    @Transactional
    public void completeExistingFirstCycle(UUID companyId) {
        Plan plan = find(companyId, true);
        if (plan == null || !plan.enabled() || plan.monthly().signum() <= 0 ||
                plan.renewalDate() == null || plan.start() == null) return;
        if (!plan.renewalDate().equals(plan.start().plusYears(1))) return;
        if (plan.start().isBefore(today().minusYears(1))) return;
        generate(plan, plan.start(), true);
    }

    static record Installment(int monthOffset, int coveredMonths) {}

    /**
     * Todos os quatro prazos somam exatamente 12 meses contratados.
     * No primeiro ano a adesão equivale à primeira mensalidade.
     */
    static List<Installment> planInstallments(String term, boolean firstCycle) {
        int step=switch(term) {
            case "ANNUAL" -> 12;
            case "SEMIANNUAL" -> 6;
            case "QUARTERLY" -> 3;
            default -> 1;
        };
        java.util.ArrayList<Installment> rows=new java.util.ArrayList<>();
        if (firstCycle) {
            rows.add(new Installment(0,1));
            for (int month=1;month<12;month+=step)
                rows.add(new Installment(month,Math.min(step,12-month)));
        } else {
            for (int month=0;month<12;month+=step)
                rows.add(new Installment(month,Math.min(step,12-month)));
        }
        return List.copyOf(rows);
    }

    private void generate(Plan plan, LocalDate start, boolean firstCycle) {
        if (plan.monthly().signum()<=0) return;
        for (Installment row:planInstallments(plan.term(),firstCycle)) {
            String note=firstCycle && row.monthOffset()==0
                    ? "Adesão (primeira mensalidade)"
                    : "Parcela do plano ("+row.coveredMonths()+" mês(es))";
            insert(plan,start,row.monthOffset(),row.coveredMonths(),note);
        }
    }

    private void insert(Plan plan, LocalDate start,int monthOffset,int coveredMonths,String note) {
        YearMonth ym=YearMonth.from(start.plusMonths(monthOffset));
        LocalDate due=ym.atDay(Math.min(plan.day(),ym.lengthOfMonth()));
        if (monthOffset==0 && due.isBefore(start)) due=start;
        BigDecimal amount=plan.monthly().multiply(BigDecimal.valueOf(coveredMonths));
        db.update("""
            INSERT INTO platform_company_invoices
              (id,company_id,reference_month,amount,due_date,status,notes,created_at,updated_at)
            VALUES(gen_random_uuid(),?,?,?,?,'PENDING',?,NOW(),NOW())
            ON CONFLICT(company_id,reference_month) DO NOTHING
            """,plan.id(),ym.atDay(1),amount,due,note);
    }

    public Map<String,Object> status(UUID companyId) {
        Plan p=find(companyId,false);
        if(p==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Empresa não encontrada.");
        long days=p.renewalDate()==null?-1:ChronoUnit.DAYS.between(today(),p.renewalDate());
        return Map.of(
                "enabled",p.enabled(), "choice",p.choice(), "nextRenewalDate",
                p.renewalDate()==null?"":p.renewalDate().toString(),
                "remainingDays",days, "showModal",p.enabled() && days>=0 && days<=15 && "AUTO".equals(p.choice()),
                "label","NON_RENEW".equals(p.choice())?"Cancelado (plano não renovado)":"Renovação automática");
    }

    @Transactional
    public Map<String,Object> decide(UUID companyId,String selection) {
        if(selection==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Escolha a opção de renovação.");
        String action=selection.trim().toUpperCase(Locale.ROOT);
        if(!List.of("RENEW_NOW","AUTOMATIC","NON_RENEW").contains(action))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Opção de renovação inválida.");
        Plan plan=find(companyId,true);
        if(plan==null || !plan.enabled())throw new ResponseStatusException(HttpStatus.CONFLICT,"Contrato sem renovação automática configurada.");
        long days=ChronoUnit.DAYS.between(today(),plan.renewalDate());
        if(days>15 || days<0)throw new ResponseStatusException(HttpStatus.CONFLICT,"A renovação só pode ser definida nos 15 dias anteriores ao vencimento.");
        if("NON_RENEW".equals(action)) {
            db.update("UPDATE platform_companies SET renewal_choice='NON_RENEW',updated_at=NOW() WHERE id=?",companyId);
        } else if("AUTOMATIC".equals(action)) {
            db.update("UPDATE platform_companies SET renewal_choice='AUTO_ACK',updated_at=NOW() WHERE id=?",companyId);
        } else {
            generate(plan,plan.renewalDate(),false);
            db.update("UPDATE platform_companies SET renewal_choice='AUTO',next_renewal_date=?,updated_at=NOW() WHERE id=?",
                    plan.renewalDate().plusYears(1),companyId);
        }
        return status(companyId);
    }

    /**
     * Regera somente os ciclos que venceram e cujos clientes escolheram
     * renovação automática. A chave (empresa, competência) impede duplicação.
     */
    @Scheduled(cron="0 0 2 * * *",zone="America/Sao_Paulo")
    @Transactional
    public void renewDue() {
        List<UUID> due=db.query("""
            SELECT id FROM platform_companies
             WHERE advance_billing_enabled=TRUE AND renewal_choice IN ('AUTO','AUTO_ACK')
               AND status IN ('ACTIVE','TRIAL') AND next_renewal_date<=?
            ORDER BY next_renewal_date LIMIT 100
            """,(rs,n)->rs.getObject(1,UUID.class),today());
        for(UUID id:due) {
            Plan p=find(id,true);
            if(p==null || p.renewalDate()==null || p.renewalDate().isAfter(today()))continue;
            // Recupera agendamento após indisponibilidade sem emitir ciclos duplicados.
            LocalDate beginning=p.renewalDate();
            generate(p,beginning,false);
            db.update("UPDATE platform_companies SET next_renewal_date=?,renewal_choice='AUTO',updated_at=NOW() WHERE id=?",
                    beginning.plusYears(1),id);
        }
    }

    /**
     * O banco é gravado antes de falar com o Asaas. A reserva única de
     * gs_invoice_checkout e seu estado CREATING impedem reenvio após timeout.
     * Sem financeiras configuradas, mantém pendente em vez de simular cobrança.
     */
    @Scheduled(fixedDelay=15000,initialDelay=12000)
    public void issuePending() {
        // Cada fatura é emitida pelo botão do ADM; a emissão em massa exige opt-in.
        if(!autoIssueBatches || !gateway.ready())return;
        List<UUID> invoices=db.query("""
            SELECT i.id FROM platform_company_invoices i
            JOIN platform_companies c ON c.id=i.company_id
            LEFT JOIN gs_invoice_checkout chk ON chk.invoice_id=i.id
            WHERE c.advance_billing_enabled=TRUE
              AND c.status IN ('ACTIVE','TRIAL')
              AND c.payment_method='BOLETO'
              AND c.document ~ '^[0-9]{11}([0-9]{3})?$'
              AND i.status='PENDING' AND chk.invoice_id IS NULL
            ORDER BY i.due_date,i.id LIMIT 25
            """,(rs,n)->rs.getObject(1,UUID.class));
        for(UUID id:invoices) {
            try { gateway.checkout(id); }
            catch(RuntimeException ignored) {
                // Estado remoto desconhecido não é reemitido sem conciliação.
            }
        }
    }
}
