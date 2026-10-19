package br.com.gestaosoftware.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Adaptador DNS opcional: somente zona previamente controlada, sem sobrescrever entradas de terceiros.
 * Não ativa um domínio por si só. HTTPS e identidade do tenant são validados em separado.
 */
@Service
public class CloudDnsService {
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 private final String provider,token,zoneId,suffix;
 private final boolean enabled;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
     .followRedirects(HttpClient.Redirect.NEVER).build();
 public CloudDnsService(JdbcTemplate jdbc,ObjectMapper json,
      @Value("${app.cloud.orchestrator-enabled:false}") boolean enabled,
      @Value("${app.cloud.dns-provider:MANUAL}") String provider,
      @Value("${app.cloud.cloudflare-token:}") String token,
      @Value("${app.cloud.cloudflare-zone-id:}") String zoneId,
      @Value("${app.provisioning.host-suffix:gestaosoftware.com.br}") String suffix){
    this.jdbc=jdbc;this.json=json;this.enabled=enabled;
    this.provider=provider.strip().toUpperCase(Locale.ROOT);this.token=token;this.zoneId=zoneId;this.suffix=suffix.toLowerCase(Locale.ROOT);
 }
 public String mode(){return provider;}
 private String url(){
   if(!enabled || !"CLOUDFLARE".equals(provider)||token.isBlank()||!zoneId.matches("[a-fA-F0-9]{32}"))
     throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure o DNS Cloudflare após homologação do orquestrador.");
   return "https://api.cloudflare.com/client/v4/zones/"+zoneId+"/dns_records";
 }
 private JsonNode send(String method,String url,Object body) throws Exception {
    HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
       .header("Authorization","Bearer "+token).header("Accept","application/json");
    if(body!=null)b.header("Content-Type","application/json");
    HttpRequest req=b.method(method,body==null?HttpRequest.BodyPublishers.noBody()
        :HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    HttpResponse<String> response=http.send(req,HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()<200||response.statusCode()>=300||response.body().length()>300000)
       throw new IllegalStateException("Provedor DNS não confirmou a operação. HTTP "+response.statusCode());
    JsonNode data=json.readTree(response.body());
    if(!data.path("success").asBoolean(false))throw new IllegalStateException("Provedor DNS não confirmou a operação.");
    return data.path("result");
 }
 /** A empresa só recebe o DNS se já tiver endereço exclusivo, VPS e pagamento verificado (controller). */
 @Transactional
 public Map<String,Object> configure(UUID companyId){
    String endpoint=url();
    // Serializa o mesmo tenant mesmo quando duas pessoas clicam simultaneamente.
    jdbc.queryForObject("SELECT company_id FROM gs_cloud_domains WHERE company_id=? FOR UPDATE",UUID.class,companyId);
    List<Map<String,Object>> rows=jdbc.query("""
      SELECT d.hostname,d.target_vps_id,v.ip_address FROM gs_cloud_domains d
      JOIN platform_vps v ON v.id=d.target_vps_id WHERE d.company_id=? AND v.active=TRUE
      """,(rs,n)->Map.of("hostname",rs.getString(1),"vpsId",rs.getObject(2,UUID.class),
        "ip",rs.getString(3)==null?"":rs.getString(3)),companyId);
    if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Reserve primeiro um subdomínio numa VPS ativa.");
    Map<String,Object> item=rows.get(0);
    String host,ip;
    try{host=CloudDnsValidation.requireHost((String)item.get("hostname"),suffix);
       ip=CloudDnsValidation.requirePublicIpv4((String)item.get("ip"));}
    catch(IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,ex.getMessage());}
    UUID vps=(UUID)item.get("vpsId");
    List<Map<String,Object>> existing=jdbc.query("SELECT hostname,vps_id,record_id,ip_address FROM gs_cloud_dns_records WHERE company_id=?",
       (rs,n)->Map.of("hostname",rs.getString(1),"vpsId",rs.getObject(2,UUID.class),
            "recordId",rs.getString(3)==null?"":rs.getString(3),"ip",rs.getString(4)),companyId);
    if(!existing.isEmpty() && (!host.equals(existing.get(0).get("hostname")) || !vps.equals(existing.get(0).get("vpsId"))))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"DNS antigo precisa de migração supervisionada; não substitua outro cliente.");
    try{
       String checkUrl=endpoint+"?type=A&name="+URLEncoder.encode(host,StandardCharsets.UTF_8);
       JsonNode records=send("GET",checkUrl,null);
       String tracked=existing.isEmpty()?"":(String)existing.get(0).get("recordId");
       String recordId="";
       for(JsonNode node:records){
           String id=node.path("id").asText();
           boolean belongsToTenant=("gestao-cloud:"+companyId).equals(node.path("comment").asText());
           boolean trackedEntry=!tracked.isEmpty() && tracked.equals(id);
           // Reconciliar somente registros previamente identificados pelo mesmo tenant.
           if(!belongsToTenant || !node.path("type").asText().equals("A"))
              throw new ResponseStatusException(HttpStatus.CONFLICT,"Já existe DNS de outra origem neste endereço. Não será sobrescrito.");
           if(!recordId.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Múltiplos registros A; conciliação manual necessária.");
           recordId=id;
       }
       if(!tracked.isEmpty() && recordId.isEmpty())
          throw new ResponseStatusException(HttpStatus.CONFLICT,"Registro DNS anterior desapareceu; revise antes de recriar.");
       Map<String,Object> payload=Map.of("type","A","name",host,"content",ip,"ttl",120,"proxied",false,
           "comment","gestao-cloud:"+companyId);
       JsonNode result=send(recordId.isEmpty()?"POST":"PUT",
           recordId.isEmpty()?endpoint:endpoint+"/"+recordId,payload);
       String issued=result.path("id").asText("");
       if(!issued.matches("[a-zA-Z0-9_-]{10,100}"))throw new IllegalStateException("Provedor DNS não retornou ID válido.");
       jdbc.update("""
         INSERT INTO gs_cloud_dns_records(company_id,vps_id,hostname,ip_address,record_id,status)
         VALUES(?,?,?,?,?,'READY') ON CONFLICT(company_id) DO UPDATE SET
         ip_address=EXCLUDED.ip_address,record_id=EXCLUDED.record_id,status='READY',updated_at=NOW()
         """,companyId,vps,host,ip,issued);
       return Map.of("hostname",host,"dnsStatus","READY","vpsId",vps,"ip",ip,
           "message","Registro A configurado; ainda falta HTTPS e validação do tenant.");
    }catch(ResponseStatusException e){throw e;}
    catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Não foi possível confirmar DNS com o provedor. Verifique a zona e tente novamente.");}
 }
 /** Durante fila aprovada, cria DNS se Cloudflare opt-in estiver ligado.
  * No modo MANUAL não chama serviços externos. */
 public void configureIfRequired(UUID companyId){
    if(!enabled || !"CLOUDFLARE".equals(provider))return;
    Boolean ready=jdbc.queryForObject("""
       SELECT EXISTS(SELECT 1 FROM gs_cloud_dns_records r
         JOIN gs_cloud_domains d ON d.company_id=r.company_id
         JOIN platform_vps v ON v.id=d.target_vps_id
         WHERE r.company_id=? AND r.vps_id=d.target_vps_id AND r.hostname=d.hostname
           AND r.ip_address=v.ip_address AND r.status='READY')
       """,Boolean.class,companyId);
    if(!Boolean.TRUE.equals(ready))configure(companyId);
 }
 public void requirePrepared(UUID companyId,UUID vpsId,String host){
    if(!enabled || !"CLOUDFLARE".equals(provider))return;
    if(!Boolean.TRUE.equals(jdbc.queryForObject("""
      SELECT EXISTS(SELECT 1 FROM gs_cloud_dns_records r JOIN platform_vps v ON v.id=r.vps_id
       WHERE r.company_id=? AND r.vps_id=? AND r.hostname=? AND r.ip_address=v.ip_address AND r.status='READY')
      """,Boolean.class,companyId,vpsId,host)))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Configure o DNS Cloudflare antes de provisionar os módulos.");
 }
}
