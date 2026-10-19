package br.com.gestaosoftware.control.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** Plano de controle de VPS próprias: reserva domínio, monitora agente e recomenda capacidade.
 *  DNS, TLS e instalação continuam sob o agente externo e NÃO são simulados por esta classe. */
@Service
public class CloudInfrastructureService {
 private final JdbcTemplate jdbc;
 private final ProvisioningAgentAuth auth;
 private final String suffix;
 private final ObjectMapper json;
 private final CloudDnsService dns;
 private final boolean orchestratorEnabled;
 private static final Set<String> RESERVED = Set.of("www","api","app","admin","painel","mail","smtp","ftp","root","gestao","status","monitor","dev","staging","localhost","ns1","ns2","cloud","suporte","help");
 public record DomainInput(String slug,UUID vpsId) {}
 public record Heartbeat(String agentVersion, Double cpuPercent, Double memoryPercent, Double diskPercent, Integer runningTenants) {}
 public CloudInfrastructureService(JdbcTemplate jdbc, ProvisioningAgentAuth auth, ObjectMapper json, CloudDnsService dns,
        @Value("${app.provisioning.host-suffix:gestaosoftware.com.br}") String configuredSuffix,
        @Value("${app.cloud.orchestrator-enabled:false}") boolean orchestratorEnabled) {
    this.jdbc=jdbc;this.auth=auth;this.json=json;this.dns=dns;this.orchestratorEnabled=orchestratorEnabled;
    suffix=configuredSuffix.toLowerCase(Locale.ROOT).replaceFirst("^\\.","");
    if(!suffix.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+"))throw new IllegalArgumentException("Domínio base inválido.");
 }
 public String suffix(){return suffix;}
 public Map<String,Object> overview(){
    List<Map<String,Object>> nodes=jdbc.query("""
      SELECT v.id,v.name,v.provider,v.active,v.hostname,v.ip_address,v.max_companies,v.cpu_cores,v.ram_gb,v.storage_gb,
      (SELECT COUNT(*) FROM platform_company_hosting h WHERE h.vps_id=v.id) AS hosted_count,
      (SELECT COUNT(*) FROM gs_cloud_domains d WHERE d.target_vps_id=v.id AND NOT EXISTS(SELECT 1 FROM platform_company_hosting h WHERE h.company_id=d.company_id AND h.vps_id=v.id)) AS reserved_count,
      a.cpu_percent,a.memory_percent,a.disk_percent,a.running_tenants,a.agent_version,a.last_seen,
      (a.last_seen >= NOW()-INTERVAL '3 minutes') AS agent_online
      FROM platform_vps v LEFT JOIN gs_cloud_agent_heartbeats a ON a.vps_id=v.id ORDER BY v.name
      """,(rs,n)->{
       Map<String,Object> node=new LinkedHashMap<>();
       UUID id=rs.getObject("id",UUID.class);
       boolean online=rs.getBoolean("agent_online")&&!rs.wasNull(),active=rs.getBoolean("active");
       int hosted=rs.getInt("hosted_count"),reserved=rs.getInt("reserved_count"),max=rs.getInt("max_companies");
       Double cpu=num(rs.getBigDecimal("cpu_percent")),mem=num(rs.getBigDecimal("memory_percent")),disk=num(rs.getBigDecimal("disk_percent"));
       boolean healthy=active&&online&&hosted+reserved<max&&cpu!=null&&mem!=null&&disk!=null&&cpu<75&&mem<80&&disk<80;
       node.put("id",id);node.put("name",rs.getString("name"));node.put("provider",rs.getString("provider"));
       node.put("hostname",rs.getString("hostname"));node.put("ipAddress",rs.getString("ip_address"));node.put("active",active);
       node.put("maxCompanies",max);node.put("usedCompanies",hosted+reserved);node.put("remainingSlots",Math.max(0,max-hosted-reserved));
       node.put("cpuCores",num(rs.getBigDecimal("cpu_cores")));node.put("ramGb",num(rs.getBigDecimal("ram_gb")));node.put("storageGb",num(rs.getBigDecimal("storage_gb")));
       node.put("cpuPercent",cpu);node.put("memoryPercent",mem);node.put("diskPercent",disk);
       node.put("agentOnline",online);node.put("agentVersion",rs.getString("agent_version"));node.put("lastSeen",rs.getString("last_seen"));
       node.put("healthyForStart",healthy);node.put("status",!active?"DISABLED":!online?"OFFLINE":healthy?"AVAILABLE":"REVIEW");return node;
    });
    List<Map<String,Object>> domains=jdbc.query("""
      SELECT d.company_id,d.hostname,d.target_vps_id,d.status,c.trade_name,v.name AS vps_name,v.ip_address AS dns_target,dr.status AS dns_status
      FROM gs_cloud_domains d JOIN platform_companies c ON c.id=d.company_id
      JOIN platform_vps v ON v.id=d.target_vps_id LEFT JOIN gs_cloud_dns_records dr ON dr.company_id=d.company_id ORDER BY d.created_at DESC
      """,(rs,n)->{
        Map<String,Object> d=new LinkedHashMap<>();d.put("companyId",rs.getObject("company_id",UUID.class));d.put("hostname",rs.getString("hostname"));
        d.put("vpsId",rs.getObject("target_vps_id",UUID.class));d.put("status",rs.getString("status"));d.put("companyName",rs.getString("trade_name"));d.put("vpsName",rs.getString("vps_name"));d.put("dnsTarget",rs.getString("dns_target"));d.put("dnsStatus",rs.getString("dns_status"));return d;
    });
    Map<String,Object> out=new LinkedHashMap<>();out.put("baseDomain",suffix);out.put("servers",nodes);out.put("domains",domains);
    out.put("registeredServers",nodes.size());out.put("onlineServers",nodes.stream().filter(x->Boolean.TRUE.equals(x.get("agentOnline"))).count());
    out.put("domainCount",domains.size());out.put("dnsProvider",dns.mode());out.put("orchestratorEnabled",orchestratorEnabled);out.put("message","A reserva não cria DNS ou VPS. A URL só é entregue após pagamento verificado, implantação real e validação HTTPS/tenant.");
    return out;
 }
 private static Double num(BigDecimal v){return v==null?null:v.doubleValue();}
 private static double requirePercentage(Double value,String name){if(value==null||!Double.isFinite(value)||value<0||value>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Métrica inválida: "+name);return value;}
 @Transactional
 public Map<String,Object> heartbeat(String token,UUID vpsId,Heartbeat h){
    auth.requireAgent(token,vpsId);
    if(h==null || h.agentVersion()==null || !h.agentVersion().matches("[a-zA-Z0-9._-]{2,40}") || h.runningTenants()==null || h.runningTenants()<0 || h.runningTenants()>1000000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dados de agente inválidos.");
    double cpu=requirePercentage(h.cpuPercent(),"CPU"),mem=requirePercentage(h.memoryPercent(),"RAM"),disk=requirePercentage(h.diskPercent(),"disco");
    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_vps WHERE id=? AND active=TRUE)",Boolean.class,vpsId)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"VPS desativada ou não cadastrada.");
    jdbc.update("""
      INSERT INTO gs_cloud_agent_heartbeats(vps_id,agent_version,cpu_percent,memory_percent,disk_percent,running_tenants,last_seen)
      VALUES(?,?,?,?,?,?,NOW()) ON CONFLICT(vps_id) DO UPDATE SET agent_version=EXCLUDED.agent_version,
      cpu_percent=EXCLUDED.cpu_percent,memory_percent=EXCLUDED.memory_percent,disk_percent=EXCLUDED.disk_percent,
      running_tenants=EXCLUDED.running_tenants,last_seen=NOW()
      """,vpsId,h.agentVersion(),cpu,mem,disk,h.runningTenants());
    return Map.of("accepted",true,"vpsId",vpsId.toString());
 }
 private String slug(String requested){
    String s=requested==null?"":requested.strip().toLowerCase(Locale.ROOT);
    if(!s.matches("[a-z0-9](?:[a-z0-9-]{1,46}[a-z0-9])") || s.contains("--") || RESERVED.contains(s))
     throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Escolha um subdomínio com 3 a 48 letras, números ou hífens, sem palavras reservadas.");
    return s;
 }
 public Map<String,Object> preview(String requested,UUID companyId){
    String hostname=slug(requested)+"."+suffix;
    boolean occupied;
    if(companyId==null){
      occupied=Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM gs_cloud_domains WHERE hostname=?) OR
               EXISTS(SELECT 1 FROM platform_company_hosting WHERE lower(domain)=? OR lower(subdomain)=?)
        """,Boolean.class,hostname,hostname,hostname));
    }else{
      occupied=Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM gs_cloud_domains WHERE hostname=? AND company_id<>?) OR
               EXISTS(SELECT 1 FROM platform_company_hosting WHERE company_id<>? AND (lower(domain)=? OR lower(subdomain)=?))
        """,Boolean.class,hostname,companyId,companyId,hostname,hostname));
    }
    boolean own=companyId!=null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gs_cloud_domains WHERE hostname=? AND company_id=?)",Boolean.class,hostname,companyId));
    return Map.of("hostname",hostname,"available",!occupied,"alreadyReservedForCompany",own,"dnsMode","WILDCARD_OR_A_RECORD","note","A reserva exige uma VPS escolhida; DNS/TLS serão validados antes de publicar.");
 }

 /** Avalia todas as VPS com a capacidade comprometida e a métrica observada, sem criar recursos. */
 public Map<String,Object> recommend(UUID companyId){
    CloudPlacementPolicy.Demand demand=demand(companyId);
    List<Map<String,Object>> choices=new ArrayList<>();
    for(CloudPlacementPolicy.Node node : placementNodes(companyId)){
        CloudPlacementPolicy.Decision result=CloudPlacementPolicy.evaluate(node,demand);
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("vpsId",node.id());row.put("name",node.name());row.put("eligible",result.eligible());
        row.put("score",result.score());row.put("reasons",result.reasons());
        row.put("freeCpuAfter",result.freeCpuAfter());row.put("freeRamGbAfter",result.freeRamAfter());
        row.put("freeDiskGbAfter",result.freeDiskAfter());choices.add(row);
    }
    choices.sort(Comparator.comparing((Map<String,Object> n)->!(Boolean)n.get("eligible"))
            .thenComparing((Map<String,Object> n)->-(Double)n.get("score"))
            .thenComparing(n->(String)n.get("name")));
    Map<String,Object> response=new LinkedHashMap<>();
    response.put("enabledForAutomaticReservation",orchestratorEnabled);
    response.put("required",Map.of("vcpu",demand.cpu(),"ramGb",demand.ramGb(),"diskGb",demand.diskGb()));
    response.put("candidates",choices);
    response.put("recommendedVpsId",choices.stream().filter(n->Boolean.TRUE.equals(n.get("eligible"))).map(n->n.get("vpsId")).findFirst().orElse(null));
    response.put("warning","Estimativa conservadora; sem testes de pico, desempenho de disco, rede, jurisdição ou redundância.");
    return response;
 }
 private CloudPlacementPolicy.Demand demand(UUID companyId){
    List<String> estimates=jdbc.query("SELECT estimation_json FROM gs_company_capacity WHERE company_id=?",
            (rs,n)->rs.getString(1),companyId);
    if(estimates.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Conclua o dimensionamento antes de reservar capacidade.");
    try{
        JsonNode node=json.readTree(estimates.get(0));
        if(!"START".equals(node.path("tier").asText()) || node.path("requiresArchitectureReview").asBoolean())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Business/Enterprise ainda requer projeto e aprovação técnica antes de reservar VPS.");
        return new CloudPlacementPolicy.Demand(node.path("recommendedCpuVcpu").asDouble(0),
            node.path("recommendedRamGb").asDouble(0),node.path("estimatedStorageGb").asDouble(0));
    }catch(ResponseStatusException e){throw e;}
    catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Dimensionamento inválido. Refaça a análise.");}
 }
 private List<CloudPlacementPolicy.Node> placementNodes(UUID companyId){
    return jdbc.query("""
      SELECT v.id,v.name,v.active,v.max_companies,v.cpu_cores,v.ram_gb,v.storage_gb,
        a.cpu_percent,a.memory_percent,a.disk_percent,
        (a.last_seen >= NOW()-INTERVAL '3 minutes') agent_online,
        (SELECT COUNT(*) FROM platform_company_hosting h WHERE h.vps_id=v.id AND h.company_id<>?)
        +(SELECT COUNT(*) FROM gs_cloud_domains d WHERE d.target_vps_id=v.id AND d.company_id<>?
          AND NOT EXISTS(SELECT 1 FROM platform_company_hosting h WHERE h.company_id=d.company_id AND h.vps_id=v.id)) used_slots,
        (SELECT COUNT(*) FROM platform_company_hosting h WHERE h.vps_id=v.id AND h.company_id<>?
          AND NOT EXISTS(SELECT 1 FROM gs_cloud_capacity_reservations r WHERE r.company_id=h.company_id)) unmanaged,
        (SELECT COALESCE(SUM(r.cpu_vcpu),0) FROM gs_cloud_capacity_reservations r WHERE r.vps_id=v.id AND r.company_id<>?) committed_cpu,
        (SELECT COALESCE(SUM(r.ram_gb),0) FROM gs_cloud_capacity_reservations r WHERE r.vps_id=v.id AND r.company_id<>?) committed_ram,
        (SELECT COALESCE(SUM(r.storage_gb),0) FROM gs_cloud_capacity_reservations r WHERE r.vps_id=v.id AND r.company_id<>?) committed_disk
      FROM platform_vps v LEFT JOIN gs_cloud_agent_heartbeats a ON a.vps_id=v.id ORDER BY v.id
      """,(rs,n)->new CloudPlacementPolicy.Node(rs.getString("id"),rs.getString("name"),rs.getBoolean("active"),
         rs.getBoolean("agent_online"),rs.getInt("used_slots"),rs.getInt("max_companies"),rs.getInt("unmanaged"),
         rs.getDouble("cpu_cores"),rs.getDouble("ram_gb"),rs.getDouble("storage_gb"),
         rs.getBigDecimal("cpu_percent")==null?Double.NaN:rs.getDouble("cpu_percent"),
         rs.getBigDecimal("memory_percent")==null?Double.NaN:rs.getDouble("memory_percent"),
         rs.getBigDecimal("disk_percent")==null?Double.NaN:rs.getDouble("disk_percent"),
         rs.getDouble("committed_cpu"),rs.getDouble("committed_ram"),rs.getDouble("committed_disk")),
        companyId,companyId,companyId,companyId,companyId,companyId);
 }
 /** Adição transacional da reserva de recursos. Executar sempre depois do SELECT FOR UPDATE da VPS. */
 private void reserveCapacity(UUID companyId,UUID vpsId){
    CloudPlacementPolicy.Demand required=demand(companyId);
    CloudPlacementPolicy.Node node=placementNodes(companyId).stream()
       .filter(n->n.id().equals(vpsId.toString())).findFirst()
       .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"VPS não encontrada."));
    CloudPlacementPolicy.Decision evaluation=CloudPlacementPolicy.evaluate(node,required);
    if(!evaluation.eligible())throw new ResponseStatusException(HttpStatus.CONFLICT,
      "VPS inadequada: "+String.join("; ",evaluation.reasons()));
    jdbc.update("""
       INSERT INTO gs_cloud_capacity_reservations(company_id,vps_id,cpu_vcpu,ram_gb,storage_gb,status)
       VALUES(?,?,?,?,?,'RESERVED') ON CONFLICT(company_id) DO UPDATE SET
       vps_id=EXCLUDED.vps_id,cpu_vcpu=EXCLUDED.cpu_vcpu,ram_gb=EXCLUDED.ram_gb,
       storage_gb=EXCLUDED.storage_gb,status='RESERVED',updated_at=NOW()
       """,companyId,vpsId,required.cpu(),required.ramGb(),required.diskGb());
 }
 /** Toda atribuição automática bloqueia as VPS em ordem estável antes de classificar.
  * Isso impede duas transações concorrentes de reservarem a última quota. */
 @Transactional
 public Map<String,Object> autoReserve(UUID companyId,String requestedSlug,String operator){
    if(!orchestratorEnabled)throw new ResponseStatusException(HttpStatus.CONFLICT,
        "Orquestrador desativado até aprovação dos testes.");
    // Mesma ordem de locks usada em reserve(): empresa -> VPS.
    // Evita deadlock entre reserva manual e automática da mesma empresa.
    if(jdbc.query("SELECT id FROM platform_companies WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),companyId).isEmpty())
       throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Empresa não encontrada.");
    jdbc.query("SELECT id FROM platform_vps WHERE active=TRUE ORDER BY id FOR UPDATE",(rs,n)->rs.getString(1));
    Map<String,Object> result=recommend(companyId);
    @SuppressWarnings("unchecked") List<Map<String,Object>> candidates=(List<Map<String,Object>>)result.get("candidates");
    for(Map<String,Object> c : candidates){
       if(Boolean.TRUE.equals(c.get("eligible"))){
           return reserve(companyId,new DomainInput(requestedSlug,UUID.fromString((String)c.get("vpsId"))),operator);
       }
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT,"Nenhuma VPS possui agente online, métricas recentes e capacidade reservável. Confira a lista de motivos no painel.");
 }
 /** Um domínio só pode pertencer a um cliente; a reserva persiste em transação e reflete a Hospedagem existente. */
 @Transactional
 public Map<String,Object> reserve(UUID companyId,DomainInput request,String operator){
    if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Escolha subdomínio e VPS.");
    String hostname=slug(request.slug())+"."+suffix;
    // v106: o lock por empresa vem antes do lock da VPS. Impede duas reservas simultâneas
    // para destinos diferentes de sobrescreverem silenciosamente os dados do mesmo cliente.
    List<UUID> lockedCompany=jdbc.query("SELECT id FROM platform_companies WHERE id=? FOR UPDATE",
        (rs,n)->rs.getObject(1,UUID.class),companyId);
    if(lockedCompany.isEmpty())
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Empresa não encontrada.");
    // v106: falha anterior pode ter deixado contêineres e volumes ativos. Não atribuir
    // capacidade ou endereço novamente sem uma conciliação supervisionada.
    if(Boolean.TRUE.equals(jdbc.queryForObject("""
       SELECT EXISTS(SELECT 1 FROM gs_cloud_capacity_reservations
         WHERE company_id=? AND status='HELD_FAILED')
       """,Boolean.class,companyId)))
       throw new ResponseStatusException(HttpStatus.CONFLICT,
         "Implantação anterior falhou e mantém recursos reservados. Solicite revisão da VPS antes de reservar novamente.");
    if(Boolean.TRUE.equals(jdbc.queryForObject("""
       SELECT EXISTS(SELECT 1 FROM gs_cloud_domains WHERE company_id=? AND status='ERROR')
       """,Boolean.class,companyId)))
       throw new ResponseStatusException(HttpStatus.CONFLICT,
         "Endereço em estado de erro. Verifique o ambiente anterior antes de alterar a reserva.");
    // Não se muda o endereço de um ambiente ativo pela reserva; requer procedimento de migração próprio.
    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gs_deployments WHERE company_id=? AND status IN ('QUEUED','PROVISIONING','VERIFYING','ACTIVE'))",Boolean.class,companyId)))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Empresa com implantação em andamento ou ativa. Alteração exige migração planejada.");
    UUID vpsId=request.vpsId();
    if(vpsId==null || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_vps WHERE id=? AND active=TRUE)",Boolean.class,vpsId)))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Escolha uma VPS cadastrada e ativa.");
    if(Boolean.TRUE.equals(jdbc.queryForObject("""
       SELECT EXISTS(SELECT 1 FROM platform_company_hosting WHERE company_id<>? AND (lower(domain)=? OR lower(subdomain)=?))
       OR EXISTS(SELECT 1 FROM gs_cloud_domains WHERE company_id<>? AND hostname=?)
       """,Boolean.class,companyId,hostname,hostname,companyId,hostname)))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Esse endereço já pertence a outra empresa.");
    // Serializa reservas concorrentes para esta VPS, inclusive quando empresas diferentes escolhem o último slot.
    jdbc.queryForObject("SELECT id FROM platform_vps WHERE id=? FOR UPDATE",UUID.class,vpsId);
    Integer assigned=jdbc.queryForObject("SELECT count(*) FROM platform_company_hosting WHERE vps_id=? AND company_id<>?",Integer.class,vpsId,companyId);
    Integer reserved=jdbc.queryForObject("""
       SELECT COUNT(*) FROM gs_cloud_domains d WHERE d.target_vps_id=? AND d.company_id<>?
        AND NOT EXISTS(SELECT 1 FROM platform_company_hosting h WHERE h.company_id=d.company_id AND h.vps_id=?)
       """,Integer.class,vpsId,companyId,vpsId);
    Integer limit=jdbc.queryForObject("SELECT max_companies FROM platform_vps WHERE id=?",Integer.class,vpsId);
    if(assigned+reserved>=limit)throw new ResponseStatusException(HttpStatus.CONFLICT,"VPS sem vagas. Cadastre outro servidor ou aumente a capacidade após análise.");
    if(orchestratorEnabled){
        boolean dnsAssigned=Boolean.TRUE.equals(jdbc.queryForObject("""
          SELECT EXISTS(SELECT 1 FROM gs_cloud_dns_records WHERE company_id=? AND (hostname<>? OR vps_id<>?))
          """,Boolean.class,companyId,hostname,vpsId));
        if(dnsAssigned)throw new ResponseStatusException(HttpStatus.CONFLICT,
           "O DNS publicado precisa ser migrado de forma supervisionada antes de mudar a VPS.");
        reserveCapacity(companyId,vpsId);
    }
    try{
     jdbc.update("""
       INSERT INTO gs_cloud_domains(company_id,hostname,target_vps_id) VALUES(?,?,?)
       ON CONFLICT(company_id) DO UPDATE SET hostname=EXCLUDED.hostname,target_vps_id=EXCLUDED.target_vps_id,
       status='RESERVED',updated_at=NOW()
       """,companyId,hostname,vpsId);
     jdbc.update("""
       INSERT INTO platform_company_hosting(company_id,vps_id,subdomain,dns_mode,environment_name,updated_at)
       VALUES(?,?,?,'SUBDOMAIN','managed-cloud',NOW())
       ON CONFLICT(company_id) DO UPDATE SET vps_id=EXCLUDED.vps_id,subdomain=EXCLUDED.subdomain,
       dns_mode='SUBDOMAIN',updated_at=NOW()
       """,companyId,vpsId,hostname);
     jdbc.update("INSERT INTO gs_cloud_audit(id,company_id,vps_id,action,operator,message) VALUES(?,?,?,'DOMAIN_RESERVED',?,?)",
       UUID.randomUUID(),companyId,vpsId,operator==null?"system":operator,hostname);
    }catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Endereço reservado por outra empresa. Escolha outro.");}
    return Map.of("hostname",hostname,"status","RESERVED","companyId",companyId.toString(),"vpsId",vpsId.toString());
 }
 /** Barreira adicional opt-in; não modifica instalações v103 até ativação consciente. */
 public void validateCloudDeployment(UUID companyId,UUID vpsId,String hostname){
    dns.requirePrepared(companyId,vpsId,hostname);
    if(orchestratorEnabled && !Boolean.TRUE.equals(jdbc.queryForObject(
       "SELECT EXISTS(SELECT 1 FROM gs_cloud_capacity_reservations WHERE company_id=? AND vps_id=? AND status='RESERVED')",
       Boolean.class,companyId,vpsId)))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Capacidade não foi reservada pelo orquestrador.");
    if(!Boolean.TRUE.equals(jdbc.queryForObject("""
      SELECT EXISTS(SELECT 1 FROM gs_cloud_domains d JOIN gs_cloud_agent_heartbeats a ON a.vps_id=d.target_vps_id
      JOIN platform_vps v ON v.id=d.target_vps_id
      WHERE d.company_id=? AND d.target_vps_id=? AND d.hostname=? AND v.active=TRUE
        AND a.last_seen >= NOW()-INTERVAL '3 minutes' AND a.cpu_percent<75 AND a.memory_percent<80 AND a.disk_percent<80
      )
      """,Boolean.class,companyId,vpsId,hostname)))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Confirme a reserva do subdomínio e aguarde o agente VPS ficar online com capacidade disponível.");
 }
}
