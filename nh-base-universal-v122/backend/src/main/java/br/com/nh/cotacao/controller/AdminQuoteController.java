package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.AdminDtos.AdminQuoteResponse;
import br.com.nh.cotacao.dto.AdminDtos.DeleteSummary;
import br.com.nh.cotacao.dto.AdminDtos.UpdateQuoteStatusRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateQuoteConsultantRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateAdminQuoteDetailsRequest;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.AdminActivityService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/quotes")
public class AdminQuoteController {
    private final AdminActivityService service;

    public AdminQuoteController(AdminActivityService service) { this.service = service; }

    @GetMapping
    public List<AdminQuoteResponse> list() { return service.quotes(); }

    @PatchMapping("/{id}/consultant")
    public AdminQuoteResponse updateConsultant(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateQuoteConsultantRequest request,
            Authentication auth
    ) {
        return service.updateQuoteConsultant(id, request, username(auth));
    }

    @PatchMapping("/{id}/details")
    public AdminQuoteResponse updateDetails(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAdminQuoteDetailsRequest request,
            Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.updateQuoteDetails(id, request, principal.username(), principal.role());
    }

    @PatchMapping("/{id}/status")
    public AdminQuoteResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateQuoteStatusRequest request,
            Authentication auth
    ) {
        return service.updateQuoteStatus(id, request, username(auth));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.deleteQuote(id, username(auth));
    }

    @DeleteMapping
    public DeleteSummary deleteAll(Authentication auth) {
        return service.deleteAllQuotes(username(auth));
    }

    private String username(Authentication auth) {
        return ((PortalPrincipal) auth.getPrincipal()).username();
    }
}
