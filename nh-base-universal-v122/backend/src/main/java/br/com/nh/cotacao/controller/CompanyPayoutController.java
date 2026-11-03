package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.CompanyPayoutService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Dados bancários somente pelo administrador da empresa autenticado. */
@RestController
@RequestMapping("/api/finance-office/company-payout")
@PreAuthorize("hasRole('ADMIN')")
public class CompanyPayoutController {
    private final CompanyPayoutService service;
    public CompanyPayoutController(CompanyPayoutService service) { this.service = service; }

    @GetMapping
    public Map<String,Object> get() { return service.get(); }

    @PutMapping
    public Map<String,Object> update(@RequestBody Map<String,Object> request, Authentication auth) {
        return service.update(request, ((PortalPrincipal)auth.getPrincipal()).username());
    }

    @GetMapping("/history")
    public java.util.List<Map<String,Object>> history() { return service.history(); }
}
