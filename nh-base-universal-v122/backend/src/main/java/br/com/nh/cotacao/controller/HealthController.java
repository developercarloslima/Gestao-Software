package br.com.nh.cotacao.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import br.com.nh.cotacao.service.TenantLaunchService;
import org.springframework.beans.factory.ObjectProvider;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    @Value("${app.tenant.company-id:}") private String tenantId;
    @Value("${app.tenant.mode:false}") private boolean tenantMode;
    private final ObjectProvider<TenantLaunchService> launch;
    public HealthController(ObjectProvider<TenantLaunchService> launch){this.launch=launch;}

    @GetMapping("/tenant")
    public ResponseEntity<Map<String,Object>> tenant() {
        if (tenantId==null || !tenantId.matches("[0-9a-fA-F-]{36}")) return ResponseEntity.status(503).body(Map.of("status","NOT_CONFIGURED"));
        if(!tenantMode)return ResponseEntity.status(404).body(Map.of("status","NOT_A_TENANT"));
        TenantLaunchService svc=launch.getIfAvailable();
        if(svc==null)return ResponseEntity.status(503).body(Map.of("status","STARTING"));
        try{
            Map<String,Object> readiness=svc.status();
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("status","UP");data.put("service","nh-tenant");data.put("tenantId",tenantId);
            data.put("readyForCustomers",readiness.get("readyForCustomers"));
            data.put("pendingCount",((java.util.List<?>)readiness.get("missing")).size());
            return ResponseEntity.ok(data);
        }catch(Exception e){return ResponseEntity.status(503).body(Map.of("status","NOT_READY"));}
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "nh-cotacao-api",
                "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
