package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.FinancialProviderGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/public/financial")
public class PublicFinancialStatusController {
    private final FinancialProviderGateway provider;
    public PublicFinancialStatusController(FinancialProviderGateway provider){this.provider=provider;}
    @GetMapping("/status")
    public Map<String,Object> status(){return provider.status();}
}
