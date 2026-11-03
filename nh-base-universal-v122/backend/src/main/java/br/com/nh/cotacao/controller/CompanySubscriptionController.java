package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.platform.GestaoTenantConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** Mesmo conteúdo para ADM e Financeiro; a autorização de cada rota é feita pelo SecurityConfig. */
@RestController
@RequestMapping({"/api/admin/company-subscription", "/api/finance-office/company-subscription"})
public class CompanySubscriptionController {
    private final GestaoTenantConfigService gestao;

    public CompanySubscriptionController(GestaoTenantConfigService gestao) {
        this.gestao = gestao;
    }

    @GetMapping
    public Map<String, Object> list() {
        return gestao.companySubscription();
    }
}
