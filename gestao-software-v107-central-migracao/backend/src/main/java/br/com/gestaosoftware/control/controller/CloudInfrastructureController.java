package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.security.PlatformPrincipal;
import br.com.gestaosoftware.control.service.CloudInfrastructureService;
import br.com.gestaosoftware.control.service.CloudDnsService;
import br.com.gestaosoftware.control.service.ProvisioningService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class CloudInfrastructureController {
 private final CloudInfrastructureService cloud;
 private final CloudDnsService dns;
 private final ProvisioningService provisioning;
 public CloudInfrastructureController(CloudInfrastructureService cloud,CloudDnsService dns,ProvisioningService provisioning){
   this.cloud=cloud;this.dns=dns;this.provisioning=provisioning;
 }
 @GetMapping("/api/platform/cloud/overview") public Map<String,Object> overview(){return cloud.overview();}
 @PostMapping("/api/platform/cloud/empresas/{companyId}/configurar-dns")
 public Map<String,Object> prepareDns(@PathVariable UUID companyId){
    Map<String,Object> readiness=provisioning.readiness(companyId);
    if(!Boolean.TRUE.equals(readiness.get("paymentProviderVerified")) || !Boolean.TRUE.equals(readiness.get("contractSigned")))
       throw new ResponseStatusException(HttpStatus.CONFLICT,"Contrato assinado e pagamento confirmado são obrigatórios antes do DNS.");
    return dns.configure(companyId);
 }
 @GetMapping("/api/platform/cloud/enderecos/disponibilidade")
 public Map<String,Object> availability(@RequestParam String slug,@RequestParam(required=false) UUID companyId){return cloud.preview(slug,companyId);}
 @GetMapping("/api/platform/cloud/empresas/{companyId}/recomendacao")
 public Map<String,Object> recommendation(@PathVariable UUID companyId){return cloud.recommend(companyId);}
 public record AutoReservation(String slug) {}
 @PostMapping("/api/platform/cloud/empresas/{companyId}/reservar-automaticamente")
 public Map<String,Object> autoReserve(@PathVariable UUID companyId,@RequestBody AutoReservation body,Authentication auth){
   return cloud.autoReserve(companyId,body==null?null:body.slug(),((PlatformPrincipal)auth.getPrincipal()).username());
 }
 @PutMapping("/api/platform/cloud/empresas/{companyId}/endereco")
 public Map<String,Object> reserve(@PathVariable UUID companyId,@RequestBody CloudInfrastructureService.DomainInput input,Authentication auth){
   return cloud.reserve(companyId,input,((PlatformPrincipal)auth.getPrincipal()).username());
 }
 @PostMapping("/api/agent/cloud/heartbeat")
 public Map<String,Object> heartbeat(@RequestHeader(value="X-Provisioner-Key",required=false) String token,
  @RequestHeader(value="X-Provisioner-Vps-Id",required=false) UUID vpsId,
  @RequestBody CloudInfrastructureService.Heartbeat report){return cloud.heartbeat(token,vpsId,report);}
}
