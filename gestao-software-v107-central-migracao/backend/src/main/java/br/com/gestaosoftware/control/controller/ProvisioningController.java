package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.security.PlatformPrincipal;
import br.com.gestaosoftware.control.service.CapacitySizingService;
import br.com.gestaosoftware.control.service.AsaasCheckoutService;
import br.com.gestaosoftware.control.service.ProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class ProvisioningController {
    private final ProvisioningService svc;
    private final AsaasCheckoutService checkout;
    public ProvisioningController(ProvisioningService svc,AsaasCheckoutService checkout){this.svc=svc;this.checkout=checkout;}
    @GetMapping("/api/platform/implantacao/capacidades")
    public Map<String,Object> capabilities(){return svc.capabilities();}
    @PostMapping("/api/platform/implantacao/dimensionar")
    public Map<String,Object> sizing(@RequestBody CapacitySizingService.Assessment a){return svc.preview(a);}
    @PutMapping("/api/platform/implantacao/empresas/{id}/dimensionamento")
    public Map<String,Object> save(@PathVariable UUID id,@RequestBody CapacitySizingService.Assessment a){return svc.saveAssessment(id,a);}
    @GetMapping("/api/platform/implantacao/empresas/{id}/dimensionamento")
    public Map<String,Object> sizing(@PathVariable UUID id){return svc.getAssessment(id);}
    @GetMapping("/api/platform/implantacao/empresas/{id}/prontidao")
    public Map<String,Object> ready(@PathVariable UUID id){return svc.readiness(id);}
    @GetMapping("/api/platform/implantacao/empresas/{id}/jobs")
    public List<Map<String,Object>> jobs(@PathVariable UUID id){return svc.deployments(id);}
    @PostMapping("/api/platform/implantacao/empresas/{id}/solicitar")
    public Map<String,Object> request(@PathVariable UUID id,Authentication auth){return svc.queue(id,((PlatformPrincipal)auth.getPrincipal()).username());}
    @PostMapping("/api/platform/implantacao/jobs/{id}/validar")
    public Map<String,Object> validate(@PathVariable UUID id){return svc.verifyDeployment(id);}
    @PostMapping("/api/platform/implantacao/faturas/{invoiceId}/gerar-cobranca")
    public Map<String,Object> createPayment(@PathVariable UUID invoiceId){return checkout.checkout(invoiceId);}
    @PostMapping("/api/platform/implantacao/faturas/{invoiceId}/verificar-asaas")
    public Map<String,Object> payment(@PathVariable UUID invoiceId,@RequestBody ProvisioningService.PaymentCheck request){return svc.verifyAsaasPayment(invoiceId,request.paymentId());}
    @PostMapping("/api/public/implantacao/asaas-webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void webhook(@RequestHeader(value="asaas-access-token",required=false) String token,@RequestBody JsonNode event){svc.verifiedAsaasWebhook(token,event);}
    @PostMapping("/api/agent/implantacao/proximo")
    public Map<String,Object> claim(@RequestHeader(value="X-Provisioner-Key",required=false) String token,
         @RequestHeader(value="X-Provisioner-Vps-Id",required=false) UUID vpsId){return svc.claim(token,vpsId);}
    @PostMapping("/api/agent/implantacao/jobs/{id}/resultado")
    public Map<String,Object> report(@PathVariable UUID id,@RequestHeader(value="X-Provisioner-Key",required=false) String token,@RequestHeader(value="X-Provisioner-Vps-Id",required=false) UUID vpsId,@RequestBody ProvisioningService.JobReport r){return svc.report(token,vpsId,id,r);}
}
