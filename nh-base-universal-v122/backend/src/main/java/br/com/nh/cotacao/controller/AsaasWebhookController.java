package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.AsaasClient;
import br.com.nh.cotacao.service.CommercialFinanceService;
import br.com.nh.cotacao.service.FinancialProviderGateway;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/financial/asaas")
public class AsaasWebhookController {
    private final AsaasClient asaas;
    private final CommercialFinanceService service;
    private final FinancialProviderGateway gateway;
    public AsaasWebhookController(AsaasClient asaas, CommercialFinanceService service, FinancialProviderGateway gateway){
        this.asaas=asaas;this.service=service;this.gateway=gateway;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestHeader(value="asaas-access-token",required=false) String token,@RequestBody JsonNode event){
        if(!"ASAAS".equals(gateway.code()) || !gateway.enabled() || !asaas.validWebhookToken(token)) return ResponseEntity.status(401).build();
        service.handleAsaasWebhook(event);
        return ResponseEntity.ok().build();
    }
}
