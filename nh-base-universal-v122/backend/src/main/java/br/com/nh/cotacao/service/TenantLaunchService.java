package br.com.nh.cotacao.service;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Readiness is based on actual tenant data plus independently attested work. */
@Service
@ConditionalOnProperty(name="app.tenant.mode",havingValue="true")
public class TenantLaunchService {
    private static final List<String> REQUIRED_BASE=List.of("BRAND_REVIEWED","ADMIN_PASSWORD_ROTATED","BACKUP_RESTORE_VERIFIED");
    private final JdbcTemplate jdbc;
    @Value("${app.tenant.require-commercial-catalog:true}") private boolean requireCommercial;
    @Value("${app.tenant.require-payment-integration:false}") private boolean requirePayments;
    @Value("${app.tenant.require-ponto-setup:false}") private boolean requirePonto;

    public TenantLaunchService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,Object> status() {
        Map<String,Map<String,Object>> items=new LinkedHashMap<>();
        jdbc.query("SELECT requirement_key,approved,approved_by,approved_at,evidence_uri FROM tenant_launch_requirements ORDER BY requirement_key",rs->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("approved",rs.getBoolean("approved"));
            row.put("approvedBy",rs.getString("approved_by"));
            Object approvalDate=rs.getObject("approved_at");
            row.put("approvedAt",approvalDate==null?null:approvalDate.toString());
            row.put("evidenceUri",rs.getString("evidence_uri"));
            items.put(rs.getString("requirement_key"),row);
        });
        long activePlans=jdbc.queryForObject("SELECT COUNT(*) FROM plans WHERE active=TRUE",Long.class);
        long coverages=jdbc.queryForObject("SELECT COUNT(*) FROM plan_coverages",Long.class);
        long regulations=jdbc.queryForObject("SELECT COUNT(*) FROM site_documents WHERE document_key='VEHICLE_PROTECTION_REGULATION'",Long.class);
        boolean cataloguePopulated=activePlans>0 && coverages>0;
        boolean hasRegulation=regulations>0;
        List<String> missing=new ArrayList<>();
        for(String key:REQUIRED_BASE)if(!Boolean.TRUE.equals(items.getOrDefault(key,Map.of()).get("approved")))missing.add(key);
        if(requirePayments && !Boolean.TRUE.equals(items.getOrDefault("PAYMENT_WEBHOOK_VERIFIED",Map.of()).get("approved"))) missing.add("PAYMENT_WEBHOOK_VERIFIED");
        if(requirePonto && !Boolean.TRUE.equals(items.getOrDefault("PONTO_SETUP_REVIEWED",Map.of()).get("approved"))) missing.add("PONTO_SETUP_REVIEWED");
        if(requireCommercial){
            if(!cataloguePopulated)missing.add("BUSINESS_CATALOG_EMPTY");
            if(!hasRegulation)missing.add("REGULATION_MISSING");
            String legalClauses=System.getenv("TENANT_RESPONSIBILITY_TERM_CLAUSES");
            if(legalClauses==null || legalClauses.trim().length()<40) missing.add("RESPONSIBILITY_TERM_CLAUSES_MISSING");
            String partsClauses=System.getenv("TENANT_PARTS_DELAY_CLAUSES");
            if(partsClauses==null || partsClauses.trim().length()<80) missing.add("PARTS_DELAY_TERM_CLAUSES_MISSING");
            if(!Boolean.TRUE.equals(items.getOrDefault("BUSINESS_CATALOG_REVIEWED",Map.of()).get("approved")))missing.add("BUSINESS_CATALOG_REVIEWED");
            if(!Boolean.TRUE.equals(items.getOrDefault("REGULATION_PUBLISHED",Map.of()).get("approved")))missing.add("REGULATION_PUBLISHED");
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("readyForCustomers",missing.isEmpty());
        result.put("missing",missing);
        result.put("requirements",items);
        result.put("activePlans",activePlans);
        result.put("coverageLinks",coverages);
        result.put("regulationUploaded",hasRegulation);
        result.put("commercialModuleEnabled",requireCommercial);
        result.put("paymentModuleEnabled",requirePayments);
        result.put("pontoModuleEnabled",requirePonto);
        return result;
    }

    @Transactional
    public Map<String,Object> update(String key,boolean approved,String evidenceUri,String actor) {
        if(!Set.of("BRAND_REVIEWED","ADMIN_PASSWORD_ROTATED","BACKUP_RESTORE_VERIFIED","PAYMENT_WEBHOOK_VERIFIED","BUSINESS_CATALOG_REVIEWED","REGULATION_PUBLISHED","PONTO_SETUP_REVIEWED").contains(key))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Etapa de liberação desconhecida.");
        if(approved){
            if(evidenceUri==null || evidenceUri.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe o endereço HTTPS da evidência verificada.");
            try{
                URI uri=URI.create(evidenceUri);
                if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || evidenceUri.length()>1024)
                    throw new IllegalArgumentException();
            }catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Evidência deve ser URL HTTPS válida, sem credenciais.");}
            if(key.equals("BUSINESS_CATALOG_REVIEWED") || key.equals("REGULATION_PUBLISHED")){
                Map<String,Object> report=status();
                if(key.equals("BUSINESS_CATALOG_REVIEWED") && (((Number)report.get("activePlans")).longValue()<1 || ((Number)report.get("coverageLinks")).longValue()<1))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Cadastre planos e suas coberturas antes de aprovar o catálogo.");
                if(key.equals("REGULATION_PUBLISHED") && !Boolean.TRUE.equals(report.get("regulationUploaded")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Envie o regulamento PDF no Painel Administrativo antes da aprovação.");
            }
        }
        jdbc.update("UPDATE tenant_launch_requirements SET approved=?, approved_by=?, approved_at=CASE WHEN ? THEN NOW() ELSE NULL END, evidence_uri=?, updated_at=NOW() WHERE requirement_key=?",
            approved,actor,approved,approved?evidenceUri:null,key);
        return status();
    }
}
