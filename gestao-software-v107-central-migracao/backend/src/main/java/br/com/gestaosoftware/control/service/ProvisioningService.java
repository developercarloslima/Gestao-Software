package br.com.gestaosoftware.control.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.*;

@Service
public class ProvisioningService {
    private final JdbcTemplate jdbc;
    private final CloudInfrastructureService cloud;
    private final CloudDnsService dns;
    private final boolean cloudRoutingEnabled;
    private final boolean orchestratorEnabled;
    private final ObjectMapper json;
    private final CapacitySizingService sizing;
    private final String asaasKey, asaasBase, webhookSecret, workerToken;
    private final boolean autoProvision;
    private final boolean tenantTemplateCertified;
    private final String permittedHostSuffix;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public ProvisioningService(JdbcTemplate jdbc, ObjectMapper json, CapacitySizingService sizing, CloudInfrastructureService cloud,CloudDnsService dns,
      @Value("${app.provisioning.asaas-key:}") String asaasKey,
      @Value("${app.provisioning.asaas-base:https://api-sandbox.asaas.com/v3}") String asaasBase,
      @Value("${app.provisioning.webhook-secret:}") String webhookSecret,
      @Value("${app.provisioning.worker-token:}") String workerToken,
      @Value("${app.provisioning.auto-enabled:false}") boolean autoProvision,
      @Value("${app.provisioning.host-suffix:gestaosoftware.com.br}") String permittedHostSuffix,
      @Value("${app.provisioning.tenant-certified:false}") boolean tenantTemplateCertified,
      @Value("${app.provisioning.cloud-routing-enabled:false}") boolean cloudRoutingEnabled,
      @Value("${app.cloud.orchestrator-enabled:false}") boolean orchestratorEnabled) {
        this.jdbc=jdbc;this.json=json;this.sizing=sizing;this.cloud=cloud;this.dns=dns;this.cloudRoutingEnabled=cloudRoutingEnabled;this.orchestratorEnabled=orchestratorEnabled;this.asaasKey=asaasKey;
        this.asaasBase=asaasBase;this.webhookSecret=webhookSecret;this.workerToken=workerToken;this.autoProvision=autoProvision;
        this.tenantTemplateCertified=tenantTemplateCertified;
        this.permittedHostSuffix=permittedHostSuffix.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if (!this.permittedHostSuffix.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")) throw new IllegalArgumentException("Configure um domínio permitido para a implantação.");
    }
    public record JobReport(String status, String appUrl, String apiUrl, String externalId, String message) {}
    public record PaymentCheck(String paymentId) {}
    private String encode(Object o) {try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("Dados inválidos.");}}
    private boolean exists(UUID id) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_companies WHERE id=?)",Boolean.class,id));}
    private void requireCompany(UUID id){if(!exists(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Empresa não encontrada.");}
    public Map<String,Object> preview(CapacitySizingService.Assessment a){return sizing.estimate(a);}
    /** Capacidade REAL configurada, não uma promessa de provisionar qualquer nuvem. */
    public Map<String,Object> capabilities(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("mode", "HOMOLOGATION");
        out.put("resourceCreationEnabled",autoProvision);
        out.put("providers",List.of(
           Map.of("code","MANAGED_VPS","label","VPS gerenciada","provisionable",true,"note","Apenas START; requer agente, imagens homologadas, Traefik e DNS configurado."),
           Map.of("code","CLIENT_VPS","label","VPS do cliente","provisionable",false,"note","É preciso instalar e autorizar o agente na VPS do cliente."),
           Map.of("code","RENDER","label","Render","provisionable",false,"note","Conector API para criar serviços, bancos e domínio ainda não homologado."),
           Map.of("code","AWS","label","AWS","provisionable",false,"note","Conector ECS/Fargate, RDS, S3, DNS e segredos ainda não homologado.")
        ));
        out.put("tenantTemplateCertified",tenantTemplateCertified);
        out.put("cloudRoutingEnabled",cloudRoutingEnabled);
        out.put("cloudOrchestratorEnabled",orchestratorEnabled);
        out.put("message","Não implante clientes reais antes da certificação da base white-label e dos testes de recuperação, pagamento e isolamento.");
        return out;
    }
    private boolean allowedTenantHostname(String host) {
        return host!=null && host.length()<=240 && host.matches("(?i)[a-z0-9-]+(\\.[a-z0-9-]+)+")
            && host.toLowerCase(Locale.ROOT).endsWith("."+permittedHostSuffix);
    }


    @Transactional
    public Map<String,Object> saveAssessment(UUID id, CapacitySizingService.Assessment a){
        requireCompany(id);Map<String,Object> report=sizing.estimate(a);
        List<String> contracted=jdbc.query("SELECT module_code FROM platform_company_modules WHERE company_id=? AND enabled=TRUE",(rs,n)->rs.getString(1),id);
        if(!contracted.containsAll(a.modules())||!a.modules().containsAll(contracted))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Módulos da análise devem coincidir com os contratados pela empresa.");
        jdbc.update("INSERT INTO gs_company_capacity(company_id,input_json,estimation_json) VALUES(?,?,?) ON CONFLICT(company_id) DO UPDATE SET input_json=EXCLUDED.input_json,estimation_json=EXCLUDED.estimation_json,updated_at=NOW()",id,encode(a),encode(report));
        return getAssessment(id);
    }
    public Map<String,Object> getAssessment(UUID id){
        requireCompany(id);
        List<Map<String,Object>> rows=jdbc.query("SELECT input_json,estimation_json,updated_at FROM gs_company_capacity WHERE company_id=?",(rs,n)->{
            Map<String,Object> m=new LinkedHashMap<>();
            try{m.put("input",json.readTree(rs.getString(1)));m.put("estimate",json.readTree(rs.getString(2)));}catch(Exception e){throw new IllegalStateException(e);}
            m.put("updatedAt",rs.getString(3));return m;
        },id);
        return rows.isEmpty()?Map.of("configured",false):rows.get(0);
    }
    public Map<String,Object> readiness(UUID id){
        requireCompany(id);
        Map<String,Object> out=new LinkedHashMap<>();
        boolean assessment=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gs_company_capacity WHERE company_id=?)",Boolean.class,id));
        if(assessment){
            JsonNode input=(JsonNode)getAssessment(id).get("input");
            Set<String> recorded=new HashSet<>();
            for(JsonNode module : input.path("modules"))recorded.add(module.asText());
            Set<String> current=new HashSet<>(jdbc.query("SELECT module_code FROM platform_company_modules WHERE company_id=? AND enabled=TRUE",(rs,n)->rs.getString(1),id));
            assessment=recorded.equals(current); // Reavaliar caso o contrato mude após o dimensionamento.
        }
        boolean contract=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_company_contracts WHERE id=(SELECT id FROM platform_company_contracts WHERE company_id=? ORDER BY created_at DESC LIMIT 1) AND status='SIGNED' AND client_signed_at IS NOT NULL AND management_signed_at IS NOT NULL)",Boolean.class,id));
        boolean payment=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gs_verified_payment v JOIN platform_company_invoices i ON i.id=v.invoice_id WHERE i.company_id=? AND i.status='PAID' AND v.provider_status='RECEIVED' AND i.id=(SELECT i2.id FROM platform_company_invoices i2 WHERE i2.company_id=? ORDER BY i2.reference_month DESC LIMIT 1))",Boolean.class,id,id));
        int moduleCount=jdbc.queryForObject("SELECT COUNT(*) FROM platform_company_modules WHERE company_id=? AND enabled=TRUE",Integer.class,id);
        String companyStatus=jdbc.queryForObject("SELECT status FROM platform_companies WHERE id=?",String.class,id);
        boolean modules=moduleCount>0,allowed=List.of("ACTIVE","TRIAL").contains(companyStatus);
        boolean supportedProvider=false;
        boolean startTier=false;
        if(assessment){
            JsonNode info=(JsonNode)getAssessment(id).get("input");
            JsonNode plan=(JsonNode)getAssessment(id).get("estimate");
            supportedProvider="MANAGED_VPS".equals(info.path("hosting").asText());
            startTier="START".equals(plan.path("tier").asText()) && !plan.path("requiresArchitectureReview").asBoolean();
        }
        out.put("capacityConfigured",assessment);out.put("contractSigned",contract);out.put("paymentProviderVerified",payment);
        out.put("hasModules",modules);out.put("companyAllowed",allowed);
        out.put("providerSupported",supportedProvider);out.put("capacityApproved",startTier);
        out.put("environmentCertified",tenantTemplateCertified);
        out.put("canQueue",assessment&&contract&&payment&&modules&&allowed&&supportedProvider&&startTier&&tenantTemplateCertified);
        out.put("warning","Marcar uma mensalidade como PAGA manualmente não habilita implantação. É obrigatória verificação independente junto ao provedor de pagamento.");
        return out;
    }
    /** Compara dados recuperados diretamente no Asaas; não confia no payload do navegador/webhook. */
    @Transactional
    public Map<String,Object> verifyAsaasPayment(UUID invoiceId,String paymentId){
        if(asaasKey.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure a chave Asaas no servidor.");
        if(paymentId==null||!paymentId.matches("pay_[A-Za-z0-9]{4,100}"))throw new IllegalArgumentException("ID Asaas inválido.");
        if(!Set.of("https://api.asaas.com/v3","https://api-sandbox.asaas.com/v3").contains(asaasBase))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Base Asaas não autorizada.");
        List<Map<String,Object>> invoices=jdbc.query("SELECT company_id,amount,status FROM platform_company_invoices WHERE id=?",(rs,n)->Map.of("companyId",rs.getObject(1,UUID.class),"amount",rs.getBigDecimal(2),"status",rs.getString(3)),invoiceId);
        if(invoices.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Fatura não encontrada.");
        Map<String,Object> inv=invoices.get(0);
        JsonNode remote;
        try{
            HttpRequest req=HttpRequest.newBuilder(URI.create(asaasBase+"/payments/"+paymentId)).header("access_token",asaasKey).header("Accept","application/json").timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res=http.send(req,HttpResponse.BodyHandlers.ofString());
            if(res.statusCode()!=200)throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Não foi possível verificar a cobrança com o Asaas.");
            remote=json.readTree(res.body());
        }catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Asaas indisponível.");}
        if(!"RECEIVED".equals(remote.path("status").asText()) || !invoiceId.toString().equals(remote.path("externalReference").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Pagamento ainda não recebido ou sem referência da fatura.");
        BigDecimal received=remote.path("value").decimalValue();
        if(received.compareTo((BigDecimal)inv.get("amount"))!=0 || received.signum()<=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"O valor confirmado pelo provedor é diferente da fatura.");
        jdbc.update("INSERT INTO gs_verified_payment(invoice_id,provider,provider_payment_id,provider_status) VALUES(?,'ASAAS',?,'RECEIVED') ON CONFLICT(invoice_id) DO UPDATE SET provider_payment_id=EXCLUDED.provider_payment_id,provider_status='RECEIVED',verified_at=NOW()",invoiceId,paymentId);
        jdbc.update("UPDATE platform_company_invoices SET status='PAID',paid_at=COALESCE(paid_at,NOW()),updated_at=NOW() WHERE id=?",invoiceId);
        return readiness((UUID)inv.get("companyId"));
    }
    public void verifiedAsaasWebhook(String supplied,JsonNode event){
        checkSecret(supplied,webhookSecret,"Webhook não autorizado.");
        if(!"PAYMENT_RECEIVED".equals(event.path("event").asText()))return;
        String reference=event.path("payment").path("externalReference").asText("");
        String payment=event.path("payment").path("id").asText("");
        try{
            UUID invoiceId=UUID.fromString(reference);
            Map<String,Object> state=verifyAsaasPayment(invoiceId,payment);
            if(autoProvision && workerToken.length()>=32 && Boolean.TRUE.equals(state.get("canQueue"))){
                UUID company=jdbc.queryForObject("SELECT company_id FROM platform_company_invoices WHERE id=?",UUID.class,invoiceId);
                try{queue(company,"ASAAS_VERIFIED_AUTO");}catch(ResponseStatusException ignored){ /* falha de deploy não desfaz pagamento verificado */ }
            }
        }catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Pagamento sem referência válida.");}
    }
    private void checkSecret(String provided,String expected,String error){
        if(expected==null||expected.length()<32 || provided==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,error);
    }
    @Transactional
    public Map<String,Object> queue(UUID companyId,String requestedBy){
        Map<String,Object> pre=readiness(companyId);
        if(!tenantTemplateCertified)throw new ResponseStatusException(HttpStatus.CONFLICT,"A base universal ainda não foi certificada; implantação bloqueada para proteger os clientes.");
        if(!Boolean.TRUE.equals(pre.get("canQueue")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Conclua análise, contrato e pagamento verificado antes de provisionar.");
        Map<String,Object> sizingData=getAssessment(companyId);
        JsonNode input=(JsonNode)sizingData.get("input"),estimate=(JsonNode)sizingData.get("estimate");
        if(!"MANAGED_VPS".equals(input.path("hosting").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Adaptador desse provedor ainda não conectado. Use VPS gerenciada ou aguarde a integração.");
        if(!"START".equals(estimate.path("tier").asText()) || estimate.path("requiresArchitectureReview").asBoolean())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Perfil Business/Enterprise exige revisão técnica e teste de carga. Automação de VPS limitada ao perfil Start nesta versão.");
        List<Map<String,Object>> destinations=jdbc.query("""
           SELECT COALESCE(NULLIF(h.subdomain,''),h.domain) AS host,h.vps_id,
                  v.active,v.max_companies,
                  (SELECT COUNT(*) FROM platform_company_hosting hx WHERE hx.vps_id=h.vps_id) AS assigned_count
             FROM platform_company_hosting h LEFT JOIN platform_vps v ON v.id=h.vps_id
            WHERE h.company_id=?
           """,(rs,n)->{
                Map<String,Object> m=new HashMap<>();m.put("host",rs.getString("host"));m.put("vpsId",rs.getObject("vps_id",UUID.class));
                m.put("active",rs.getBoolean("active"));m.put("max",rs.getInt("max_companies"));m.put("assigned",rs.getInt("assigned_count"));return m;
           },companyId);
        if(destinations.isEmpty()||destinations.get(0).get("vpsId")==null||!Boolean.TRUE.equals(destinations.get(0).get("active")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Vincule uma VPS gerenciada ativa à empresa antes de provisionar.");
        Map<String,Object> target=destinations.get(0);
        if((int)target.get("assigned")>(int)target.get("max"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"VPS excedeu o número de empresas permitido. Selecione outro servidor.");
        String host=(String)target.get("host");
        if(!allowedTenantHostname(host))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Informe o subdomínio válido na Hospedagem da empresa antes de provisionar.");
        if(orchestratorEnabled)dns.configureIfRequired(companyId);
        if(cloudRoutingEnabled || orchestratorEnabled)cloud.validateCloudDeployment(companyId,(UUID)target.get("vpsId"),host);
        UUID job=UUID.randomUUID();
        try{jdbc.update("INSERT INTO gs_deployments(id,company_id,provider,target_vps_id,status,requested_host,requested_by) VALUES(?,?,?,?,'QUEUED',?,?)",job,companyId,input.path("hosting").asText(),target.get("vpsId"),host,requestedBy);}catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Já existe implantação em andamento ou ativa. Consulte o histórico.");}
        jdbc.update("UPDATE gs_cloud_domains SET status='DEPLOYING',updated_at=NOW() WHERE company_id=? AND hostname=?",companyId,host);
        audit(job,"QUEUED","Solicitação autorizada após conferência de assinatura e pagamento Asaas.");
        return deployment(job);
    }
    public List<Map<String,Object>> deployments(UUID companyId){requireCompany(companyId);return jdbc.query("SELECT id FROM gs_deployments WHERE company_id=? ORDER BY created_at DESC LIMIT 30",(rs,n)->deployment((UUID)rs.getObject(1)),companyId);}
    public Map<String,Object> deployment(UUID id){
        return jdbc.query("SELECT id,company_id,provider,target_vps_id,status,requested_host,deployment_version,app_url,api_url,external_id,last_message,created_at,claimed_at,completed_at FROM gs_deployments WHERE id=?",rs->{
            if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Implantação não encontrada.");
            Map<String,Object> m=new LinkedHashMap<>();
            for(String name:List.of("id","company_id","provider","target_vps_id","status","requested_host","deployment_version","app_url","api_url","external_id","last_message","created_at","claimed_at","completed_at")) m.put(name,rs.getObject(name));
            return m;
        },id);
    }
    private void audit(UUID id,String status,String message){jdbc.update("INSERT INTO gs_deployment_events(id,deployment_id,event,message) VALUES(?,?,?,?)",UUID.randomUUID(),id,status,message);}
    /** Credencial exclusiva de cada VPS, derivada da chave mestra que NUNCA sai da Gestão. */
    private void requireAgent(String supplied,UUID vpsId){
        if(vpsId==null || workerToken==null || workerToken.length()<32 || supplied==null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Credencial do agente ausente ou inválida.");
        try{
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(workerToken.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] bytes=mac.doFinal(vpsId.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder expected=new StringBuilder(bytes.length*2);
            for(byte b:bytes)expected.append(String.format("%02x",b&255));
            if(!MessageDigest.isEqual(expected.toString().getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Agente não autorizado para esta VPS.");
        }catch(ResponseStatusException e){throw e;}
        catch(Exception e){throw new IllegalStateException("Não foi possível verificar a identidade do agente.");}
    }
    @Transactional
    public Map<String,Object> claim(String secret,UUID allowedVps){
        requireAgent(secret,allowedVps);
        if(allowedVps==null || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_vps WHERE id=? AND active=TRUE)",Boolean.class,allowedVps)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"VPS do agente não autorizada.");
        List<UUID> found=jdbc.query("SELECT id FROM gs_deployments WHERE status='QUEUED' AND target_vps_id=? ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",(rs,n)->rs.getObject(1,UUID.class),allowedVps);
        if(found.isEmpty())return Map.of("available",false);
        UUID id=found.get(0);
        jdbc.update("UPDATE gs_deployments SET status='PROVISIONING',claimed_at=NOW(),updated_at=NOW() WHERE id=?",id);
        audit(id,"PROVISIONING","Agente de infraestrutura assumiu a solicitação.");
        Map<String,Object> result=new LinkedHashMap<>(deployment(id));
        UUID cid=(UUID)result.get("company_id");
        result.put("assessment",getAssessment(cid));
        result.put("modules",jdbc.query("SELECT module_code FROM platform_company_modules WHERE company_id=? AND enabled=TRUE",(rs,n)->rs.getString(1),cid));
        result.put("branding",jdbc.query("SELECT trade_name,platform_display_name,primary_color,secondary_color,accent_color,document FROM platform_companies WHERE id=?",rs->{
            rs.next();Map<String,Object> brand=new LinkedHashMap<>();
            brand.put("tradeName",rs.getString(1));brand.put("displayName",rs.getString(2));brand.put("primaryColor",rs.getString(3));brand.put("secondaryColor",rs.getString(4));brand.put("accentColor",rs.getString(5));brand.put("document",rs.getString(6));return brand;
        },cid));
        return result;
    }
    @Transactional
    public Map<String,Object> report(String secret,UUID agentVpsId,UUID id,JobReport report){
        requireAgent(secret,agentVpsId);
        if (agentVpsId==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Identidade da VPS obrigatória.");
        Map<String,Object> job=deployment(id);
        if(!agentVpsId.equals(job.get("target_vps_id")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Job não pertence à VPS autenticada.");
        if(!List.of("PROVISIONING","VERIFYING").contains(job.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Estado da implantação não permite alteração.");
        if(report==null||!Set.of("FAILED","VERIFYING").contains(report.status()))throw new IllegalArgumentException("Estado inválido.");
        if("FAILED".equals(report.status())){
            jdbc.update("UPDATE gs_deployments SET status='FAILED',last_message=?,updated_at=NOW() WHERE id=?",shortText(report.message()),id);
            jdbc.update("UPDATE gs_cloud_domains SET status='ERROR',updated_at=NOW() WHERE company_id=? AND hostname=?",job.get("company_id"),job.get("requested_host"));
            // Nunca devolver recursos automaticamente: um deploy que falhou pode ter contêineres/volumes ativos.
            jdbc.update("UPDATE gs_cloud_capacity_reservations SET status='HELD_FAILED',updated_at=NOW() WHERE company_id=?",job.get("company_id"));
            audit(id,"FAILED",shortText(report.message()));return deployment(id);
        }
        // O agente apenas registra URL candidata. Ela NÃO é publicada até prova de saúde externa.
        String expected=(String)job.get("requested_host");
        String url="https://"+expected;
        if(!url.equals(report.appUrl())||report.apiUrl()==null||!report.apiUrl().startsWith(url+"/"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A URL precisa coincidir com o domínio cadastrado. Somente HTTPS é permitido.");
        jdbc.update("UPDATE gs_deployments SET status='VERIFYING',app_url=?,api_url=?,external_id=?,last_message=?,updated_at=NOW() WHERE id=?",url,report.apiUrl(),shortText(report.externalId()),shortText(report.message()),id);
        audit(id,"VERIFYING","Agente reportou deploy; falta validação independente de DNS, HTTPS e health-check.");
        return deployment(id);
    }
    /** A URL não é publicada só porque o agente disse que subiu. Verificação HTTP real + identidade do tenant. */
    @Transactional
    public Map<String,Object> verifyDeployment(UUID id){
        Map<String,Object> job=deployment(id);
        if(!"VERIFYING".equals(job.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"O ambiente ainda não está pronto para validação.");
        String host=(String)job.get("requested_host");
        try{
            // Nunca consultar IP privado, loopback, link-local ou literal IP enviado por cliente.
            if(!allowedTenantHostname(host))throw new IllegalArgumentException("Hostname fora do domínio de implantação permitido.");
            for(java.net.InetAddress addr:java.net.InetAddress.getAllByName(host)){
                byte[] raw=addr.getAddress();
                if(addr.isAnyLocalAddress()||addr.isLoopbackAddress()||addr.isLinkLocalAddress()||addr.isSiteLocalAddress()||addr.isMulticastAddress()
                   || (raw.length==16 && (raw[0]&0xfe)==0xfc))throw new IllegalArgumentException("Destino de rede não autorizado.");
            }
            String url="https://"+host+"/api/health/tenant";
            HttpRequest req=HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(8)).header("Accept","application/json").build();
            HttpResponse<String> response=http.send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>8192)throw new IllegalArgumentException("Health-check indisponível.");
            JsonNode node=json.readTree(response.body());
            if(!"UP".equals(node.path("status").asText()) || !String.valueOf(job.get("company_id")).equals(node.path("tenantId").asText()))
                throw new IllegalArgumentException("A aplicação não confirmou a identidade da empresa.");
            if(!node.path("readyForCustomers").asBoolean(false))
                throw new IllegalArgumentException("A empresa ainda precisa concluir o assistente de configuração e homologação.");
        }catch(Exception e){
            jdbc.update("UPDATE gs_deployments SET last_message=?,updated_at=NOW() WHERE id=?","Aguardando validação completa (DNS, TLS, identidade e checklist de implantação). Confira a aba Implantação no painel da empresa.",id);
            return deployment(id);
        }
        jdbc.update("UPDATE gs_deployments SET status='ACTIVE',completed_at=NOW(),updated_at=NOW(),last_message='DNS, HTTPS e identidade do tenant verificados.' WHERE id=?",id);
        jdbc.update("INSERT INTO platform_company_hosting(company_id,app_url,api_url,deployment_method,updated_at) VALUES(?,?,?,'PROVISIONER',NOW()) ON CONFLICT(company_id) DO UPDATE SET app_url=EXCLUDED.app_url,api_url=EXCLUDED.api_url,deployment_method='PROVISIONER',updated_at=NOW()",
          job.get("company_id"),job.get("app_url"),job.get("api_url"));
        jdbc.update("UPDATE gs_cloud_domains SET status='ACTIVE',updated_at=NOW() WHERE company_id=? AND hostname=?",job.get("company_id"),host);
        jdbc.update("UPDATE gs_cloud_capacity_reservations SET status='ACTIVE',updated_at=NOW() WHERE company_id=?",job.get("company_id"));
        audit(id,"ACTIVE","URL liberada após confirmação real do tenant.");
        return deployment(id);
    }
    private String shortText(String value){return value==null?null:value.substring(0,Math.min(1000,value.length()));}
}
