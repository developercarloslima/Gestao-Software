package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.ConsultantRepository;
import br.com.nh.cotacao.repository.ConsultantWithdrawalRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@Service
public class FinanceOfficeService {
    private final JdbcTemplate jdbc;
    private final ConsultantRepository consultants;
    private final ConsultantWithdrawalRepository withdrawals;
    private final ConsultantCommissionGuideService commissions;
    private final FinancialProviderGateway asaas;

    public FinanceOfficeService(JdbcTemplate jdbc, ConsultantRepository consultants,
                                ConsultantWithdrawalRepository withdrawals,
                                ConsultantCommissionGuideService commissions,
                                FinancialProviderGateway asaas) {
        this.jdbc = jdbc;
        this.consultants = consultants;
        this.withdrawals = withdrawals;
        this.commissions = commissions;
        this.asaas = asaas;
    }

    @Transactional(readOnly = true)
    public Map<String,Object> dashboard(String range) {
        LocalDate[] period = resolveRange(range);
        LocalDate from = period[0], to = period[1];
        BigDecimal systemRevenue = oneDecimal("""
                select coalesce(sum(value),0) from financial_charges
                 where status='RECEIVED' and paid_at>=? and paid_at<?
                """, from.atStartOfDay().atOffset(ZoneOffset.UTC), to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        BigDecimal manualRevenue = oneDecimal("""
                select coalesce(sum(amount),0) from finance_ledger_entries
                 where entry_type='REVENUE' and status='RECEIVED' and coalesce(effective_date,due_date) between ? and ?
                """, from, to);
        BigDecimal expenses = oneDecimal("""
                select coalesce(sum(amount),0) from finance_ledger_entries
                 where entry_type in ('EXPENSE','TAX','PAYROLL') and status='PAID' and coalesce(effective_date,due_date) between ? and ?
                """, from, to);
        BigDecimal payables = oneDecimal("""
                select coalesce(sum(amount),0) from finance_ledger_entries
                 where entry_type='PAYABLE' and status in ('PENDING','OVERDUE')
                """);
        BigDecimal receivables = oneDecimal("""
                select coalesce(sum(amount),0) from finance_ledger_entries
                 where entry_type='RECEIVABLE' and status in ('PENDING','OVERDUE')
                """);
        BigDecimal overdueCustomer = oneDecimal("""
                select coalesce(sum(value),0) from financial_charges where status='OVERDUE'
                """);
        BigDecimal grossRevenue = systemRevenue.add(manualRevenue).setScale(2,RoundingMode.HALF_UP);
        BigDecimal taxes = oneDecimal("select coalesce(sum(amount),0) from finance_ledger_entries where entry_type='TAX' and status='PAID' and coalesce(effective_date,due_date) between ? and ?",from,to);
        BigDecimal payroll = oneDecimal("select coalesce(sum(amount),0) from finance_ledger_entries where entry_type='PAYROLL' and status='PAID' and coalesce(effective_date,due_date) between ? and ?",from,to);
        BigDecimal variableCosts = oneDecimal("select coalesce(sum(amount),0) from finance_ledger_entries where entry_type='EXPENSE' and status='PAID' and cost_nature='VARIABLE' and coalesce(effective_date,due_date) between ? and ?",from,to);
        BigDecimal fixedCosts = oneDecimal("select coalesce(sum(amount),0) from finance_ledger_entries where entry_type in ('EXPENSE','PAYROLL') and status='PAID' and coalesce(cost_nature,'FIXED')='FIXED' and coalesce(effective_date,due_date) between ? and ?",from,to);
        BigDecimal operatingResult = grossRevenue.subtract(expenses).setScale(2,RoundingMode.HALF_UP);
        BigDecimal ebitda = grossRevenue.subtract(expenses.subtract(taxes)).setScale(2,RoundingMode.HALF_UP);
        BigDecimal contribution = grossRevenue.subtract(variableCosts).setScale(2,RoundingMode.HALF_UP);
        BigDecimal contributionRate = grossRevenue.signum()==0?BigDecimal.ZERO:contribution.multiply(new BigDecimal("100")).divide(grossRevenue,2,RoundingMode.HALF_UP);
        BigDecimal breakEven = contribution.signum()<=0||grossRevenue.signum()==0?BigDecimal.ZERO:fixedCosts.divide(contribution.divide(grossRevenue,8,RoundingMode.HALF_UP),2,RoundingMode.HALF_UP);
        BigDecimal margin = grossRevenue.signum() == 0 ? BigDecimal.ZERO : operatingResult.multiply(new BigDecimal("100")).divide(grossRevenue,2,RoundingMode.HALF_UP);
        BigDecimal investedCapital = settingNumber("INVESTED_CAPITAL");
        BigDecimal roi = investedCapital.signum()==0?BigDecimal.ZERO:operatingResult.multiply(new BigDecimal("100")).divide(investedCapital,2,RoundingMode.HALF_UP);
        BigDecimal cashPosition = oneDecimal("select coalesce((select sum(value) from financial_charges where status='RECEIVED'),0)+coalesce((select sum(amount) from finance_ledger_entries where entry_type='REVENUE' and status='RECEIVED'),0)-coalesce((select sum(amount) from finance_ledger_entries where entry_type in ('EXPENSE','TAX','PAYROLL') and status='PAID'),0)");
        BigDecimal minimumReserve=settingNumber("MINIMUM_CASH_RESERVE");
        BigDecimal reserveGap=minimumReserve.subtract(cashPosition).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
        BigDecimal customerPortfolio=oneDecimal("select coalesce(sum(value),0) from financial_charges where status in ('RECEIVED','PENDING','CONFIRMED','OVERDUE')");
        BigDecimal delinquencyRate=customerPortfolio.signum()==0?BigDecimal.ZERO:overdueCustomer.multiply(new BigDecimal("100")).divide(customerPortfolio,2,RoundingMode.HALF_UP);
        long openWithdrawals = oneLong("select count(*) from consultant_withdrawals where status in ('REQUESTED','PROCESSING')");
        BigDecimal openWithdrawalAmount = oneDecimal("select coalesce(sum(amount),0) from consultant_withdrawals where status in ('REQUESTED','PROCESSING')");
        long unreconciled = oneLong("select count(*) from finance_ledger_entries where reconciled=false and status in ('PAID','RECEIVED')");
        BigDecimal currentAssets = cashPosition.add(receivables).setScale(2,RoundingMode.HALF_UP);
        BigDecimal currentLiabilities = payables.add(openWithdrawalAmount).setScale(2,RoundingMode.HALF_UP);
        BigDecimal managementEquity = currentAssets.subtract(currentLiabilities).setScale(2,RoundingMode.HALF_UP);
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("from",from); out.put("to",to); out.put("range",range);
        out.put("grossRevenue",grossRevenue); out.put("operatingExpenses",expenses); out.put("operatingResult",operatingResult);
        out.put("operatingMargin",margin); out.put("ebitda",ebitda); out.put("taxes",taxes); out.put("payroll",payroll);
        out.put("variableCosts",variableCosts); out.put("fixedCosts",fixedCosts); out.put("contributionMargin",contribution); out.put("contributionMarginRate",contributionRate); out.put("breakEven",breakEven); out.put("roi",roi);
        out.put("cashPosition",cashPosition); out.put("minimumCashReserve",minimumReserve); out.put("cashReserveGap",reserveGap);
        out.put("payables",payables); out.put("receivables",receivables); out.put("customerOverdue",overdueCustomer); out.put("delinquencyRate",delinquencyRate);
        out.put("openWithdrawals",openWithdrawals); out.put("openWithdrawalAmount",openWithdrawalAmount); out.put("unreconciled",unreconciled);
        out.put("dre",Map.of("revenue",grossRevenue,"operatingExpenses",expenses,"payroll",payroll,"taxes",taxes,"result",operatingResult));
        out.put("balanceSheet",Map.of("cash",cashPosition,"receivables",receivables,"assets",currentAssets,"payables",payables,"withdrawals",openWithdrawalAmount,"liabilities",currentLiabilities,"equity",managementEquity));
        out.put("risk",Map.of("delinquencyRate",delinquencyRate,"overdueAmount",overdueCustomer,"reserveGap",reserveGap,"unreconciled",unreconciled,"openWithdrawals",openWithdrawals));
        out.put("cashFlow", cashFlow(from,to));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> ledger(String type, String status, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder("select * from finance_ledger_entries where 1=1");
        List<Object> args = new ArrayList<>();
        if(type!=null&&!type.isBlank()){sql.append(" and entry_type=?");args.add(type.toUpperCase(Locale.ROOT));}
        if(status!=null&&!status.isBlank()){sql.append(" and status=?");args.add(status.toUpperCase(Locale.ROOT));}
        if(from!=null){sql.append(" and coalesce(effective_date,due_date)>=?");args.add(from);}
        if(to!=null){sql.append(" and coalesce(effective_date,due_date)<=?");args.add(to);}
        sql.append(" order by coalesce(effective_date,due_date) desc nulls last, created_at desc limit 1000");
        return jdbc.query(sql.toString(), (rs,row)->rowMap(rs), args.toArray());
    }

    @Transactional
    public Map<String,Object> createLedger(Map<String,Object> req, String username) {
        UUID id=UUID.randomUUID();
        String type=required(req,"entryType").toUpperCase(Locale.ROOT);
        String status=str(req.get("status"),"PENDING").toUpperCase(Locale.ROOT);
        BigDecimal amount=decimal(req.get("amount"));
        if(amount.signum()<0)throw new IllegalArgumentException("Valor não pode ser negativo.");
        jdbc.update("""
            insert into finance_ledger_entries(id,entry_type,status,category,counterparty,description,amount,due_date,effective_date,payment_method,external_reference,department,cost_nature,reconciled,created_by,created_at,updated_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,false,?,now(),now())
            """,id,type,status,str(req.get("category"),null),str(req.get("counterparty"),null),required(req,"description"),amount,
            date(req.get("dueDate")),date(req.get("effectiveDate")),str(req.get("paymentMethod"),null),str(req.get("externalReference"),null),str(req.get("department"),null),blank(req.get("costNature") == null ? null : String.valueOf(req.get("costNature"))),username);
        return ledgerById(id);
    }

    @Transactional
    public Map<String,Object> setLedgerStatus(UUID id,String status,LocalDate effectiveDate){
        int n=jdbc.update("update finance_ledger_entries set status=?,effective_date=coalesce(?,effective_date),updated_at=now() where id=?",status.toUpperCase(Locale.ROOT),effectiveDate,id);
        if(n==0)throw new IllegalArgumentException("Lançamento não encontrado."); return ledgerById(id);
    }

    @Transactional
    public Map<String,Object> reconcile(UUID id,String note){
        int n=jdbc.update("update finance_ledger_entries set reconciled=true,reconciliation_note=?,updated_at=now() where id=?",note,id);
        if(n==0)throw new IllegalArgumentException("Lançamento não encontrado."); return ledgerById(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> documents(String type, UUID consultantId){
        String sql="select id,document_type,consultant_id,collaborator_id,collaborator_name,collaborator_reference,withdrawal_id,reference_month,title,notes,file_name,content_type,octet_length(file_data) size_bytes,uploaded_by,created_at from finance_documents where (? is null or document_type=?) and (?::uuid is null or consultant_id=?) order by created_at desc limit 500";
        return jdbc.query(sql,(rs,row)->{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",rs.getObject("id",UUID.class));m.put("documentType",rs.getString("document_type"));m.put("consultantId",rs.getObject("consultant_id",UUID.class));m.put("collaboratorId",rs.getObject("collaborator_id",UUID.class));m.put("collaboratorName",rs.getString("collaborator_name"));m.put("collaboratorReference",rs.getString("collaborator_reference"));m.put("withdrawalId",rs.getObject("withdrawal_id",UUID.class));
            m.put("referenceMonth",rs.getObject("reference_month",LocalDate.class));m.put("title",rs.getString("title"));m.put("notes",rs.getString("notes"));m.put("fileName",rs.getString("file_name"));m.put("contentType",rs.getString("content_type"));m.put("sizeBytes",rs.getLong("size_bytes"));m.put("uploadedBy",rs.getString("uploaded_by"));m.put("createdAt",rs.getObject("created_at",OffsetDateTime.class));return m;
        },blank(type),blank(type),consultantId,consultantId);
    }

    @Transactional
    public Map<String,Object> uploadDocument(String type, UUID consultantId, UUID collaboratorId, LocalDate referenceMonth, String title, String notes, MultipartFile file, String username){
        return uploadDocument(type,consultantId,collaboratorId,null,null,null,referenceMonth,title,notes,file,username);
    }

    @Transactional
    public Map<String,Object> uploadDocument(String type, UUID consultantId, UUID collaboratorId, String collaboratorName, String collaboratorReference,
                                             UUID withdrawalId, LocalDate referenceMonth, String title, String notes, MultipartFile file, String username){
        if(file==null||file.isEmpty())throw new IllegalArgumentException("Selecione um arquivo.");
        if(file.getSize()>15*1024*1024)throw new IllegalArgumentException("Arquivo maior que 15 MB.");
        String normalizedType=type==null?"":type.trim().toUpperCase(Locale.ROOT);
        Set<String> allowed=Set.of("CONSULTANT_INVOICE","CONSULTANT_PAYMENT_RECEIPT","PAYSLIP","SUPPLIER_INVOICE","TAX_DOCUMENT","ISSUED_INVOICE","OTHER");
        if(!allowed.contains(normalizedType))throw new IllegalArgumentException("Tipo de documento financeiro inválido.");
        if(withdrawalId!=null && withdrawals.findById(withdrawalId).isEmpty())throw new IllegalArgumentException("Solicitação de saque não encontrada.");
        try{
            UUID id=UUID.randomUUID();
            jdbc.update("insert into finance_documents(id,document_type,consultant_id,collaborator_id,collaborator_name,collaborator_reference,withdrawal_id,reference_month,title,notes,file_name,content_type,file_data,uploaded_by,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())",
                    id,normalizedType,consultantId,collaboratorId,str(collaboratorName,null),str(collaboratorReference,null),withdrawalId,referenceMonth,title==null||title.isBlank()?file.getOriginalFilename():title.trim(),notes,file.getOriginalFilename(),str(file.getContentType(),"application/octet-stream"),file.getBytes(),username);
            return documents(normalizedType,consultantId).stream().filter(x->id.equals(x.get("id"))).findFirst().orElse(Map.of("id",id));
        }catch(IOException e){throw new IllegalStateException("Não foi possível salvar o arquivo.",e);}
    }

    @Transactional(readOnly = true)
    public FilePayload file(UUID id){
        return jdbc.query("select file_name,content_type,file_data from finance_documents where id=?",rs->{if(!rs.next())throw new IllegalArgumentException("Documento não encontrado.");return new FilePayload(rs.getString(1),rs.getString(2),rs.getBytes(3));},id);
    }

    @Transactional(readOnly = true)
    public FilePayload fileForConsultant(UUID id, UUID consultantId){
        return jdbc.query("select file_name,content_type,file_data from finance_documents where id=? and consultant_id=?",rs->{if(!rs.next())throw new IllegalArgumentException("Documento não encontrado para este consultor.");return new FilePayload(rs.getString(1),rs.getString(2),rs.getBytes(3));},id,consultantId);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> withdrawalQueue(){
        return withdrawals.findAllByOrderByCreatedAtDesc().stream().map(w->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",w.getId());m.put("consultantId",w.getConsultant().getId());m.put("consultantName",w.getConsultant().getName());m.put("amount",w.getAmount());m.put("fee",w.getTransactionFee());m.put("netAmount",w.getNetAmount());m.put("payoutMethod",w.getPayoutMethod());m.put("bankDetails",w.getBankDetails());m.put("status",w.getStatus());m.put("financeNote",w.getFinanceNote());m.put("createdAt",w.getCreatedAt());m.put("completedAt",w.getCompletedAt());m.put("failReason",w.getFailReason());return m;
        }).toList();
    }

    /** Permite registrar falha real do Asaas sem desfazer o status FAILED. */
    static final class AsaasTransferFailure extends RuntimeException {
        AsaasTransferFailure(String message, Throwable cause) { super(message, cause); }
    }

    static void assertManualPaidAllowed(ConsultantPayoutMethod method, WithdrawalStatus current) {
        if (method == ConsultantPayoutMethod.PIX)
            throw new IllegalArgumentException("Saque Pix só pode ser confirmado pelo webhook verificado do Asaas.");
        if (current != WithdrawalStatus.PROCESSING)
            throw new IllegalArgumentException("Aprovar/processar a TED antes de marcá-la como paga.");
    }

    @Transactional(noRollbackFor = AsaasTransferFailure.class)
    public Map<String,Object> processWithdrawal(UUID id,String note){
        ConsultantWithdrawal w=withdrawals.findByIdForUpdate(id).orElseThrow(()->new IllegalArgumentException("Solicitação de saque não encontrada."));
        if(w.getStatus()!=WithdrawalStatus.REQUESTED) throw new IllegalArgumentException("Somente solicitações pendentes podem ser processadas.");
        if(note!=null&&!note.isBlank()) w.financeNote(note);
        if(w.getPayoutMethod()==ConsultantPayoutMethod.PIX){
            if(!asaas.enabled()) throw new IllegalStateException("Configure a financeira para processar saques via Pix.");
            if(w.getPixKeyType()==null||w.getPixKey()==null||w.getPixKey().isBlank()) throw new IllegalArgumentException("A solicitação não possui chave Pix válida.");
            if(asaas.balance().compareTo(w.getAmount())<0) throw new IllegalArgumentException("Saldo da conta financeira insuficiente para processar o saque e sua tarifa.");
            try{
                var transfer=asaas.transferPix(w.getNetAmount(),w.getPixKeyType(),w.getPixKey(),"Saque de comissão - "+w.getConsultant().getName(),"withdrawal:"+w.getId());
                if(transfer.id()==null) throw new IllegalStateException("Asaas não retornou o identificador da transferência.");
                w.processing(transfer.id());
                w.financeNote((note==null||note.isBlank()?"":note.trim()+" | ")+"Pix aprovado pelo Financeiro e enviado ao Asaas. Aguardando confirmação.");
            }catch(RuntimeException ex){
                // Falha/timeout pode ocorrer após o Asaas aceitar a transferência.
                // Reservamos o valor e bloqueamos novo envio até conciliação humana.
                w.processing(null);
                w.financeNote("Resultado da transferência Pix incerto. Conferir no Asaas antes de qualquer nova tentativa.");
                withdrawals.save(w);
                throw new AsaasTransferFailure("Resultado do Pix incerto; solicitação reservada para conciliação antes de nova tentativa.", ex);
            }
        }else{
            w.processing(null);
            w.financeNote((note==null||note.isBlank()?"":note.trim()+" | ")+"TED aprovado pelo Financeiro. Aguardando pagamento manual e comprovante.");
        }
        withdrawals.save(w);
        return withdrawalQueue().stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public Map<String,Object> updateWithdrawal(UUID id,String status,String note){
        ConsultantWithdrawal w=withdrawals.findByIdForUpdate(id).orElseThrow(()->new IllegalArgumentException("Solicitação de saque não encontrada."));
        if(w.getStatus()==WithdrawalStatus.PAID)
            throw new IllegalArgumentException("Saque pago é imutável.");
        if(w.getPayoutMethod()==ConsultantPayoutMethod.PIX && w.getStatus()==WithdrawalStatus.PROCESSING)
            throw new IllegalArgumentException("Saque Pix enviado ao Asaas só pode ser concluído ou revertido pelo retorno do provedor.");
        if(note!=null&&!note.isBlank())w.financeNote(note);
        switch(status.toUpperCase(Locale.ROOT)){
            case "PAID" -> {
                assertManualPaidAllowed(w.getPayoutMethod(), w.getStatus());
                w.paid();
            }
            case "FAILED" -> w.failed(note==null?"Falha informada pelo Financeiro.":note);
            case "CANCELLED" -> w.cancelled(note==null?"Cancelado pelo Financeiro.":note);
            default -> throw new IllegalArgumentException("Status de saque inválido.");
        }
        withdrawals.save(w);
        return withdrawalQueue().stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true) public List<Map<String,Object>> commissionRules(){return commissions.rules();}
    @Transactional public void updateCommissionRule(String key,BigDecimal value,boolean enabled,String username){commissions.updateRule(key,value,enabled,username);}
    @Transactional public void setReferrer(UUID consultantId,UUID referrerId){commissions.setReferrer(consultantId,referrerId);}

    @Transactional(readOnly = true)
    public List<Map<String,Object>> consultantFinancials(){
        YearMonth ym=YearMonth.now();
        return consultants.findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole.CONSULTANT).stream().map(c->{
            var b=commissions.calculate(c.getId(),ym);Map<String,Object>m=new LinkedHashMap<>();m.put("id",c.getId());m.put("name",c.getName());m.put("career",commissions.career(c.getId(),ym));m.put("activeBase",commissions.activePayingBase(c.getId(),ym));m.put("baseRevenue",commissions.activeBaseRevenue(c.getId(),ym));m.put("sales",commissions.sales(c.getId(),ym));m.put("commission",b.total());
            Map<String,Object> ref=jdbc.query("select r.id,r.name from consultants x left join consultants r on r.id=x.referrer_consultant_id where x.id=?",rs->{if(!rs.next()||rs.getObject(1)==null)return null;Map<String,Object>x=new LinkedHashMap<>();x.put("id",rs.getObject(1,UUID.class));x.put("name",rs.getString(2));return x;},c.getId());
            m.put("referrer",ref);return m;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> careerLevels(){return jdbc.query("select * from consultant_career_levels order by min_active",(rs,row)->rowMap(rs));}
    @Transactional(readOnly = true)
    public List<Map<String,Object>> basePrizes(){return jdbc.query("select * from consultant_base_prizes where enabled=true order by active_threshold",(rs,row)->rowMap(rs));}

    @Transactional(readOnly = true)
    public List<Map<String,Object>> suppliers(){return jdbc.query("select * from finance_suppliers order by active desc,name",(rs,row)->rowMap(rs));}
    @Transactional public Map<String,Object> createSupplier(Map<String,Object> r){UUID id=UUID.randomUUID();jdbc.update("insert into finance_suppliers(id,name,document,contact,payment_terms,notes,active,created_at,updated_at) values(?,?,?,?,?,?,true,now(),now())",id,required(r,"name"),str(r.get("document"),null),str(r.get("contact"),null),str(r.get("paymentTerms"),null),str(r.get("notes"),null));return suppliers().stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow();}

    @Transactional(readOnly = true)
    public List<Map<String,Object>> cards(){return jdbc.query("select * from finance_corporate_cards order by active desc,label",(rs,row)->rowMap(rs));}
    @Transactional public Map<String,Object> createCard(Map<String,Object> r){UUID id=UUID.randomUUID();jdbc.update("insert into finance_corporate_cards(id,label,holder,last4,credit_limit,active,created_at,updated_at) values(?,?,?,?,?,true,now(),now())",id,required(r,"label"),str(r.get("holder"),null),str(r.get("last4"),null),decimal(r.get("creditLimit")));return cards().stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow();}

    @Transactional(readOnly = true)
    public List<Map<String,Object>> budgets(){return jdbc.query("select * from finance_budgets order by period_start desc,department",(rs,row)->rowMap(rs));}
    @Transactional public Map<String,Object> createBudget(Map<String,Object>r,String username){UUID id=UUID.randomUUID();jdbc.update("insert into finance_budgets(id,department,period_start,period_end,planned_amount,notes,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,now(),now())",id,required(r,"department"),date(r.get("periodStart")),date(r.get("periodEnd")),decimal(r.get("plannedAmount")),str(r.get("notes"),null),username);return budgets().stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow();}

    @Transactional(readOnly = true)
    public Map<String,Object> settings(){Map<String,Object> m=new LinkedHashMap<>();jdbc.query("select setting_key,numeric_value,text_value,updated_by,updated_at from finance_settings order by setting_key",rs->{Map<String,Object> v=new LinkedHashMap<>();v.put("numericValue",rs.getBigDecimal("numeric_value"));v.put("textValue",rs.getString("text_value"));v.put("updatedBy",rs.getString("updated_by"));v.put("updatedAt",rs.getObject("updated_at"));m.put(rs.getString("setting_key"),v);});return m;}
    @Transactional
    public Map<String,Object> updateSetting(String key,BigDecimal value,String username){if(!Set.of("INVESTED_CAPITAL","MINIMUM_CASH_RESERVE").contains(key))throw new IllegalArgumentException("Configuração financeira inválida.");if(value==null||value.signum()<0)throw new IllegalArgumentException("Valor inválido.");jdbc.update("update finance_settings set numeric_value=?,updated_by=?,updated_at=now() where setting_key=?",value,username,key);return settings();}
    private BigDecimal settingNumber(String key){BigDecimal v=jdbc.query("select numeric_value from finance_settings where setting_key=?",rs->rs.next()?rs.getBigDecimal(1):BigDecimal.ZERO,key);return v==null?BigDecimal.ZERO:v.setScale(2,RoundingMode.HALF_UP);}

    private List<Map<String,Object>> cashFlow(LocalDate from,LocalDate to){return jdbc.query("""
            select d::date day,
                   coalesce((select sum(value) from financial_charges fc where fc.status='RECEIVED' and fc.paid_at::date=d::date),0)+coalesce((select sum(amount) from finance_ledger_entries l where l.entry_type='REVENUE' and l.status='RECEIVED' and coalesce(l.effective_date,l.due_date)=d::date),0) income,
                   coalesce((select sum(amount) from finance_ledger_entries l where l.entry_type in ('EXPENSE','TAX','PAYROLL') and l.status='PAID' and coalesce(l.effective_date,l.due_date)=d::date),0) expense
              from generate_series(?::date,?::date,'1 day') d order by d
            """,(rs,row)->Map.of("date",rs.getObject("day",LocalDate.class),"income",rs.getBigDecimal("income"),"expense",rs.getBigDecimal("expense")),from,to);}
    private Map<String,Object> ledgerById(UUID id){return jdbc.query("select * from finance_ledger_entries where id=?",rs->{if(!rs.next())throw new IllegalArgumentException("Lançamento não encontrado.");return rowMap(rs);},id);}
    private Map<String,Object> rowMap(java.sql.ResultSet rs)throws java.sql.SQLException{Map<String,Object>m=new LinkedHashMap<>();var md=rs.getMetaData();for(int i=1;i<=md.getColumnCount();i++){String k=md.getColumnLabel(i);String camel=toCamel(k);Object v=rs.getObject(i);m.put(camel,v);}return m;}
    private static String toCamel(String s){StringBuilder b=new StringBuilder();boolean up=false;for(char c:s.toCharArray()){if(c=='_'){up=true;continue;}b.append(up?Character.toUpperCase(c):c);up=false;}return b.toString();}
    private LocalDate[] resolveRange(String range){LocalDate now=LocalDate.now();String r=range==null?"month":range.toLowerCase(Locale.ROOT);return switch(r){case "week"->new LocalDate[]{now.minusDays(6),now};case "semester"->new LocalDate[]{now.minusMonths(6).plusDays(1),now};case "year"->new LocalDate[]{now.withDayOfYear(1),now};default->new LocalDate[]{now.withDayOfMonth(1),now};};}
    private BigDecimal oneDecimal(String sql,Object...args){BigDecimal v=jdbc.queryForObject(sql,BigDecimal.class,args);return v==null?BigDecimal.ZERO:v.setScale(2,RoundingMode.HALF_UP);}
    private long oneLong(String sql,Object...args){Long v=jdbc.queryForObject(sql,Long.class,args);return v==null?0:v;}
    private static String required(Map<String,Object>m,String k){String v=str(m.get(k),null);if(v==null||v.isBlank())throw new IllegalArgumentException("Campo obrigatório: "+k);return v;}
    private static String str(Object o,String d){return o==null?d:String.valueOf(o).trim();}
    private static String blank(String v){return v==null||v.isBlank()?null:v.trim().toUpperCase(Locale.ROOT);}
    private static BigDecimal decimal(Object o){if(o==null||String.valueOf(o).isBlank())return BigDecimal.ZERO;return new BigDecimal(String.valueOf(o)).setScale(2,RoundingMode.HALF_UP);}
    private static LocalDate date(Object o){if(o==null||String.valueOf(o).isBlank())return null;return LocalDate.parse(String.valueOf(o));}
    public record FilePayload(String fileName,String contentType,byte[] bytes){}
}
