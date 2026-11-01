package br.com.gestaosoftware.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class AsaasCheckoutService implements PlatformPaymentProvider {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String key,base,webhookSecret;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AsaasCheckoutService(JdbcTemplate db,ObjectMapper json,
        @Value("${app.provisioning.asaas-key:}") String key,
        @Value("${app.provisioning.asaas-base:https://api-sandbox.asaas.com/v3}") String base,
        @Value("${app.provisioning.webhook-secret:}") String webhookSecret){
        this.db=db;this.json=json;this.key=key;this.base=base;this.webhookSecret=webhookSecret;
    }
    @Override public String code(){return "ASAAS";}
    @Override public boolean ready(){return key!=null && !key.isBlank() && webhookSecret!=null && !webhookSecret.isBlank() &&
            Set.of("https://api.asaas.com/v3","https://api-sandbox.asaas.com/v3").contains(base);}
    private void configured(){
        if(!ready())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure credenciais Asaas no servidor.");
    }
    private JsonNode call(String method,String route,Object body){
        try{
            HttpRequest.Builder req=HttpRequest.newBuilder(URI.create(base+route)).timeout(Duration.ofSeconds(25))
                .header("access_token",key).header("User-Agent","GestaoSoftware/1.0").header("Accept","application/json");
            if("POST".equals(method))req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            else req.GET();
            HttpResponse<String> res=client.send(req.build(),HttpResponse.BodyHandlers.ofString());
            if(res.statusCode()<200||res.statusCode()>=300)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Asaas recusou a operação. Verifique dados e logs internos.");
            return json.readTree(res.body());
        }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Não foi possível comunicar com o Asaas.");}
    }
    public Map<String,Object> checkout(UUID invoice){
        List<Map<String,Object>> items=db.query("""
         SELECT i.company_id,i.amount,i.due_date,i.status,c.trade_name,c.document,c.contact_email,c.payment_method
           FROM platform_company_invoices i JOIN platform_companies c ON c.id=i.company_id WHERE i.id=?
         """,(rs,n)->{
            Map<String,Object> m=new HashMap<>();for(String k:List.of("trade_name","document","contact_email","payment_method","status"))m.put(k,rs.getString(k));
            m.put("companyId",rs.getObject("company_id",UUID.class));m.put("amount",rs.getBigDecimal("amount"));m.put("due",rs.getDate("due_date").toLocalDate());return m;
         },invoice);
        if(items.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Fatura não encontrada.");
        Map<String,Object> row=items.get(0);
        if("PAID".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Fatura já paga.");
        if(((BigDecimal)row.get("amount")).signum()<=0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Fatura sem valor positivo.");
        String document=String.valueOf(row.get("document")==null?"":row.get("document")).replaceAll("\\D","");
        if(!document.matches("[0-9]{11}|[0-9]{14}"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Cadastre CPF/CNPJ válido para a cobrança.");
        String method=(String)row.get("payment_method");
        if("DEBITO_AUTOMATICO".equals(method) || "PIX_AUTOMATICO".equals(method))throw new ResponseStatusException(HttpStatus.CONFLICT,"Este método recorrente ainda não está integrado ao checkout. Configure cobrança convencional de boleto ou Pix para implantação.");
        configured();
        List<Map<String,Object>> exist=db.query("SELECT status,invoice_url,provider_payment_id FROM gs_invoice_checkout WHERE invoice_id=?",(rs,n)->{
            Map<String,Object> m=new HashMap<>();m.put("status",rs.getString(1));m.put("invoiceUrl",rs.getString(2));m.put("paymentId",rs.getString(3));return m;
        },invoice);
        if(!exist.isEmpty()){
            if("CREATED".equals(exist.get(0).get("status")))return exist.get(0);
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Cobrança em criação ou estado desconhecido. Confirme no Asaas antes de tentar novamente para evitar duplicidade.");
        }
        // Reserva local única: uma chamada concorrente não deve duplicar pagamentos.
        int reserved=db.update("INSERT INTO gs_invoice_checkout(invoice_id,status) VALUES(?,'CREATING') ON CONFLICT DO NOTHING",invoice);
        if(reserved!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"A cobrança já está em criação.");
        String custId=null;
        try{
            String query="/customers?cpfCnpj="+URLEncoder.encode(document,StandardCharsets.UTF_8);
            JsonNode customers=call("GET",query,null).path("data");
            if(customers.isArray())for(JsonNode item:customers){
                if(((UUID)row.get("companyId")).toString().equals(item.path("externalReference").asText())){
                    custId=item.path("id").asText();break;
                }
            }
            if(custId==null){
                Map<String,Object> customer=new LinkedHashMap<>();
                customer.put("name",row.get("trade_name"));customer.put("cpfCnpj",document);
                customer.put("externalReference",row.get("companyId").toString());
                Object email=row.get("contact_email");if(email!=null&&!String.valueOf(email).isBlank())customer.put("email",email);
                JsonNode created=call("POST","/customers",customer);custId=created.path("id").asText();
            }
            if(!custId.matches("cus_[A-Za-z0-9]{4,100}"))throw new IllegalStateException("Asaas não retornou cliente válido.");
            db.update("UPDATE gs_invoice_checkout SET provider_customer_id=?,updated_at=NOW() WHERE invoice_id=?",custId,invoice);
            Map<String,Object> payment=Map.of("customer",custId,"billingType","UNDEFINED","value",row.get("amount"),
               "dueDate",row.get("due").toString(),"description","Mensalidade Gestão Software - "+row.get("trade_name"),"externalReference",invoice.toString());
            JsonNode paid=call("POST","/payments",payment);
            String payId=paid.path("id").asText(),url=paid.path("invoiceUrl").asText();
            if(!payId.matches("pay_[A-Za-z0-9]{4,100}")||!url.startsWith("https://"))
               throw new IllegalStateException("Cobrança criada com resposta incompleta. Consulte o Asaas antes de tentar novamente.");
            db.update("UPDATE gs_invoice_checkout SET provider_payment_id=?,invoice_url=?,status='CREATED',updated_at=NOW() WHERE invoice_id=?",payId,url,invoice);
            return Map.of("status","CREATED","invoiceUrl",url,"paymentId",payId);
        }catch(Exception e){
            // Não tentamos recriar cobrança automaticamente após timeout/resposta incerta.
            if(e instanceof ResponseStatusException re)throw re;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Estado da cobrança incerto; consulte o Asaas antes de repetir.");
        }
    }
}
