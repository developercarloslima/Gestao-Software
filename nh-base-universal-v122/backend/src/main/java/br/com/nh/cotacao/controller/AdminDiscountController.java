package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.AdminDtos.DiscountRuleAdminResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpsertDiscountRuleRequest;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.AdminDiscountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/catalog/discounts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDiscountController {
    private final AdminDiscountService service;

    public AdminDiscountController(AdminDiscountService service) {
        this.service = service;
    }

    @GetMapping
    public List<DiscountRuleAdminResponse> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DiscountRuleAdminResponse create(
            @Valid @RequestBody UpsertDiscountRuleRequest request, Authentication auth
    ) {
        return service.create(request, username(auth));
    }

    @PatchMapping("/{id}")
    public DiscountRuleAdminResponse update(
            @PathVariable Long id, @Valid @RequestBody UpsertDiscountRuleRequest request, Authentication auth
    ) {
        return service.update(id, request, username(auth));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication auth) {
        service.delete(id, username(auth));
    }

    private String username(Authentication auth) {
        return ((PortalPrincipal) auth.getPrincipal()).username();
    }
}
