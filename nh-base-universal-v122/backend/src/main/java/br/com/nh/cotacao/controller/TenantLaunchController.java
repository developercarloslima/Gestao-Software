package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.TenantLaunchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.Map;

/** Only ADMIN users can approve release checklists; it does not itself provision infrastructure. */
@RestController
@RequestMapping("/api/admin/implantacao")
@ConditionalOnProperty(name="app.tenant.mode", havingValue="true")
public class TenantLaunchController {
    private final TenantLaunchService service;
    public TenantLaunchController(TenantLaunchService service){this.service=service;}
    public record Approval(boolean approved,String evidenceUri){}
    @GetMapping public Map<String,Object> status(){return service.status();}
    @PutMapping("/{key}") public Map<String,Object> update(@PathVariable String key,@RequestBody Approval request,Authentication auth){
        if(request==null)throw new IllegalArgumentException("Informe a aprovação e sua evidência.");
        return service.update(key,request.approved(),request.evidenceUri(),auth.getName());
    }
}
