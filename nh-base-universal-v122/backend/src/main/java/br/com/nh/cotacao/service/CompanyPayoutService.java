package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.PixKeyType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Repasse da própria subconta da empresa para o destino da própria empresa.
 * Desativado por padrão, limitado ao Sandbox até homologação explícita.
 * Reserva o lote antes da chamada externa; timeout fica incerto sem retry automático.
 */
@Service
public class CompanyPayoutService {
    private static final ZoneId COMPANY_ZONE = ZoneId.of("America/Sao_Paulo");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FinancialDestinationCipher cipher;
    private final FinancialProviderGateway gateway;
    private final AsaasClient asaas;
    private final boolean sandboxExecutionEnabled;
    private final String asaasBaseUrl;

    public CompanyPayoutService(JdbcTemplate jdbc, ObjectMapper json, FinancialDestinationCipher cipher,
                                FinancialProviderGateway gateway, AsaasClient asaas,
                                @Value("${app.company-payout.sandbox-execution-enabled:false}") boolean sandboxExecutionEnabled,
                                @Value("${app.asaas.base-url:https://api-sandbox.asaas.com/v3}") String asaasBaseUrl) {
        this.jdbc = jdbc; this.json = json; this.cipher = cipher;
        this.gateway = gateway; this.asaas = asaas;
        this.sandboxExecutionEnabled = sandboxExecutionEnabled;
        this.asaasBaseUrl = asaasBaseUrl;
    }

    public Map<String,Object> get() {
        var list=jdbc.query("""
            SELECT destination_type,masked_destination,auto_daily_enabled,owner_confirmed,
                   minimum_reserve,transfer_fee_estimate,updated_by,updated_at
              FROM finance_company_payout_profile WHERE id=1
            """,(rs,n)->{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("configured",true);
            m.put("destinationType",rs.getString("destination_type"));
            m.put("maskedDestination",rs.getString("masked_destination"));
            m.put("autoDailyEnabled",rs.getBoolean("auto_daily_enabled"));
            m.put("ownerConfirmed",rs.getBoolean("owner_confirmed"));
            m.put("minimumReserve",rs.getBigDecimal("minimum_reserve"));
            m.put("transferFeeEstimate",rs.getBigDecimal("transfer_fee_estimate"));
            m.put("updatedBy",rs.getString("updated_by"));
            m.put("updatedAt",rs.getObject("updated_at"));
            m.put("sandboxExecutionEnabled",sandboxReady());
            // Nunca devolvemos dados de conta, chave Pix ou texto cifrado.
            return m;
        });
        return list.isEmpty() ? Map.of("configured",false,"autoDailyEnabled",false,"sandboxExecutionEnabled",sandboxReady()) : list.get(0);
    }

    @Transactional
    public Map<String,Object> update(Map<String,Object> request,String username) {
        if (request==null) throw new IllegalArgumentException("Informe os dados de recebimento.");
        String type=required(request,"destinationType").toUpperCase(Locale.ROOT);
        ObjectNode destination=json.createObjectNode();
        String masked;
        if ("PIX".equals(type)) {
            PixKeyType keyType;
            try { keyType=PixKeyType.valueOf(required(request,"pixKeyType").toUpperCase(Locale.ROOT)); }
            catch(IllegalArgumentException ex){ throw new IllegalArgumentException("Tipo de chave Pix inválido."); }
            String key=required(request,"pixKey").trim();
            if(key.length()>255||key.length()<4||key.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Informe uma chave Pix válida.");
            if(keyType==PixKeyType.CPF||keyType==PixKeyType.CNPJ) {
                key=key.replaceAll("\\D","");
                if(key.length()!=(keyType==PixKeyType.CPF?11:14)) throw new IllegalArgumentException("CPF/CNPJ Pix inválido.");
            }
            destination.put("pixKeyType",keyType.name());
            destination.put("pixKey",key);
            masked="Pix "+keyType+" ••••"+key.substring(Math.max(0,key.length()-4));
        } else if ("BANK".equals(type)) {
            String bank=digits(required(request,"bankCode"),3,3);
            String doc=digits(required(request,"cpfCnpj"),11,14);
            if(doc.length()!=11&&doc.length()!=14) throw new IllegalArgumentException("Documento bancário inválido.");
            String agency=required(request,"agency");
            String account=required(request,"account");
            String digit=required(request,"accountDigit");
            String owner=required(request,"ownerName");
            String accountType=required(request,"bankAccountType");
            if(!Set.of("CONTA_CORRENTE","CONTA_POUPANCA").contains(accountType))
                throw new IllegalArgumentException("Tipo de conta inválido.");
            if(agency.length()>20||account.length()>30||digit.length()>4||owner.length()>160)
                throw new IllegalArgumentException("Dados bancários excedem o limite.");
            ObjectNode bankObj=json.createObjectNode();
            bankObj.putObject("bank").put("code",bank);
            bankObj.put("ownerName",owner);
            bankObj.put("cpfCnpj",doc);
            bankObj.put("agency",agency);
            bankObj.put("account",account);
            bankObj.put("accountDigit",digit);
            bankObj.put("bankAccountType",accountType);
            destination.set("bankAccount",bankObj);
            masked="Banco "+bank+" ••••"+account.substring(Math.max(0,account.length()-4));
        } else throw new IllegalArgumentException("Tipo de destino inválido.");

        BigDecimal reserve=money(request.get("minimumReserve"));
        BigDecimal fee=money(request.get("transferFeeEstimate"));
        boolean confirmed=Boolean.TRUE.equals(request.get("ownerConfirmed"));
        boolean daily=Boolean.TRUE.equals(request.get("autoDailyEnabled"));
        if(daily && !confirmed) throw new IllegalArgumentException("Confirme a titularidade da conta antes de ativar o repasse.");
        String encrypted=cipher.encrypt(destination.toString());
        jdbc.update("""
             INSERT INTO finance_company_payout_profile(
               id,destination_type,encrypted_destination,masked_destination,
               auto_daily_enabled,owner_confirmed,minimum_reserve,transfer_fee_estimate,updated_by,updated_at)
             VALUES(1,?,?,?,?,?,?,?,?,NOW())
             ON CONFLICT (id) DO UPDATE SET
                 destination_type=excluded.destination_type,
                 encrypted_destination=excluded.encrypted_destination,
                 masked_destination=excluded.masked_destination,
                 auto_daily_enabled=excluded.auto_daily_enabled,
                 owner_confirmed=excluded.owner_confirmed,
                 minimum_reserve=excluded.minimum_reserve,
                 transfer_fee_estimate=excluded.transfer_fee_estimate,
                 updated_by=excluded.updated_by,updated_at=NOW()
             """,type,encrypted,masked,daily,confirmed,reserve,fee,username);
        return get();
    }

    public List<Map<String,Object>> history() {
        return jdbc.query("""
           SELECT run_date,amount,estimated_fee,reserved_amount,status,created_at,updated_at
             FROM finance_company_payout_batches ORDER BY run_date DESC LIMIT 90
           """,(rs,n)->Map.of(
                "date",rs.getDate("run_date").toLocalDate().toString(),
                "amount",rs.getBigDecimal("amount"),
                "estimatedFee",rs.getBigDecimal("estimated_fee"),
                "reservedAmount",rs.getBigDecimal("reserved_amount"),
                "status",rs.getString("status"),
                "createdAt",rs.getObject("created_at").toString(),
                "updatedAt",rs.getObject("updated_at").toString()));
    }

    /**
     * Render Cron é o agendador preferido para produção. Este agendamento interno
     * é exclusivo da homologação Sandbox, com flag explicitamente habilitada.
     */
    @Scheduled(cron="${app.company-payout.daily-cron:0 0 18 * * *}",zone="America/Sao_Paulo")
    public void runSandboxDaily() {
        if(!sandboxReady()||!gateway.enabled()) return;
        var profiles=jdbc.query("""
            SELECT encrypted_destination,auto_daily_enabled,owner_confirmed,
                   minimum_reserve,transfer_fee_estimate
              FROM finance_company_payout_profile WHERE id=1
            """,(rs,n)->Map.<String,Object>of(
                "destination",rs.getString("encrypted_destination"),
                "enabled",rs.getBoolean("auto_daily_enabled"),
                "confirmed",rs.getBoolean("owner_confirmed"),
                "reserve",rs.getBigDecimal("minimum_reserve"),
                "fee",rs.getBigDecimal("transfer_fee_estimate")));
        if(profiles.isEmpty()) return;
        Map<String,Object> p=profiles.get(0);
        if(!Boolean.TRUE.equals(p.get("enabled"))||!Boolean.TRUE.equals(p.get("confirmed"))) return;

        BigDecimal available=asaas.balance();
        BigDecimal consultantPending=sum("""
            SELECT COALESCE(SUM(amount),0) FROM consultant_withdrawals WHERE status IN ('REQUESTED','PROCESSING')
            """);
        BigDecimal accountsPayable=sum("""
            SELECT COALESCE(SUM(amount),0) FROM finance_ledger_entries
             WHERE status='PENDING' AND entry_type IN ('PAYABLE','EXPENSE','TAX','PAYROLL')
            """);
        BigDecimal reserveDb=sum("""
            SELECT COALESCE(MAX(numeric_value),0) FROM finance_settings WHERE setting_key='MINIMUM_CASH_RESERVE'
            """);
        BigDecimal reserve=((BigDecimal)p.get("reserve")).max(reserveDb);
        BigDecimal obligations=consultantPending.add(accountsPayable);
        BigDecimal fee=(BigDecimal)p.get("fee");
        BigDecimal amount=CompanyPayoutCalculator.amount(available,reserve,obligations,fee);
        if(amount.compareTo(BigDecimal.ONE)<0) return;

        LocalDate date=LocalDate.now(COMPANY_ZONE);
        UUID id=UUID.randomUUID();
        String ref="company-payout:"+date;
        // A reserva local é definitiva mesmo quando a resposta da rede é incerta.
        int claimed=jdbc.update("""
            INSERT INTO finance_company_payout_batches(
               id,run_date,amount,estimated_fee,reserved_amount,status,external_reference)
            VALUES(?,?,?,?,?,'SUBMITTING',?) ON CONFLICT (run_date) DO NOTHING
            """,id,date,amount,fee,reserve.add(obligations),ref);
        if(claimed!=1) return;
        try {
            JsonNode target=json.readTree(cipher.decrypt((String)p.get("destination")));
            FinancialProvider.TransferData transfer;
            if(target.hasNonNull("pixKey")) {
                transfer=asaas.transferPix(amount,PixKeyType.valueOf(target.path("pixKeyType").asText()),
                    target.path("pixKey").asText(),"Repasse diário da empresa",ref);
            } else {
                transfer=asaas.transferBank(amount,(ObjectNode)target.path("bankAccount"),ref);
            }
            if(transfer.id()==null || transfer.id().isBlank()) throw new IllegalStateException("Transferência sem identificador.");
            jdbc.update("""
                UPDATE finance_company_payout_batches SET status='PROCESSING',provider_transfer_id=?,updated_at=NOW()
                 WHERE id=? AND status='SUBMITTING'
                """,transfer.id(),id);
        } catch(Exception ex) {
            // Nunca reenvia automaticamente: o Asaas pode ter aceitado a transferência.
            jdbc.update("""
                UPDATE finance_company_payout_batches SET status='UNCERTAIN',updated_at=NOW()
                 WHERE id=? AND status='SUBMITTING'
                """,id);
        }
    }

    @Transactional
    public void handleVerifiedTransferEvent(JsonNode event) {
        if(event==null || !event.path("event").asText("").startsWith("TRANSFER_")) return;
        JsonNode transfer=event.path("transfer");
        String ref=transfer.path("externalReference").asText("");
        String providerId=transfer.path("id").asText("");
        if(!ref.startsWith("company-payout:") || providerId.isBlank()) return;
        String status=switch(event.path("event").asText("")){
            case "TRANSFER_DONE" -> "DONE";
            case "TRANSFER_FAILED" -> "FAILED";
            case "TRANSFER_CANCELLED" -> "CANCELLED";
            default -> null;
        };
        if(status==null) return;
        // Somente o evento verificado do Asaas pode concluir o lote.
        jdbc.update("""
            UPDATE finance_company_payout_batches SET status=?,provider_transfer_id=?,updated_at=NOW()
             WHERE external_reference=? AND (provider_transfer_id=? OR provider_transfer_id IS NULL)
               AND status IN ('SUBMITTING','PROCESSING','UNCERTAIN')
            """,status,providerId,ref,providerId);
    }

    private boolean sandboxReady(){
        return sandboxExecutionEnabled && "https://api-sandbox.asaas.com/v3".equals(asaasBaseUrl.replaceAll("/+$",""));
    }
    private BigDecimal sum(String sql){
        BigDecimal n=jdbc.queryForObject(sql,BigDecimal.class);
        return n==null?BigDecimal.ZERO:n;
    }
    private static String required(Map<String,Object> request,String key){
        Object v=request.get(key);
        if(v==null||String.valueOf(v).isBlank())throw new IllegalArgumentException("Informe "+key+".");
        return String.valueOf(v).trim();
    }
    private static String digits(String text,int min,int max){
        String d=text.replaceAll("\\D","");
        if(d.length()<min||d.length()>max)throw new IllegalArgumentException("Campo numérico inválido.");
        return d;
    }
    private static BigDecimal money(Object v){
        if(v==null||String.valueOf(v).isBlank())return BigDecimal.ZERO;
        try {
            BigDecimal n=new BigDecimal(String.valueOf(v)).setScale(2,java.math.RoundingMode.HALF_UP);
            if(n.signum()<0||n.compareTo(new BigDecimal("100000000.00"))>0)throw new IllegalArgumentException();
            return n;
        }catch(Exception ex){throw new IllegalArgumentException("Valor financeiro inválido.");}
    }
}
