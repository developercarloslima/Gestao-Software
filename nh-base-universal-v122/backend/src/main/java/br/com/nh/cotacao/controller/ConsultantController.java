package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.PortalDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.ConsultantService;
import br.com.nh.cotacao.service.PortalUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/consultants")
public class ConsultantController {
    private final ConsultantService service;
    private final PortalUserService portalUserService;

    public ConsultantController(ConsultantService service, PortalUserService portalUserService) {
        this.service = service;
        this.portalUserService = portalUserService;
    }

    @GetMapping
    public List<ConsultantResponse> active(Authentication auth) {
        PortalPrincipal principal = principal(auth);
        var linked = portalUserService.linkedConsultantId(principal.username());
        if (linked.isPresent()) return List.of(service.active(linked.get()));
        return service.active();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultantResponse create(@Valid @RequestBody CreateConsultantRequest request, Authentication auth) {
        PortalPrincipal principal = principal(auth);
        if (portalUserService.linkedConsultantId(principal.username()).isPresent()) {
            throw new IllegalArgumentException("Este login já está vinculado a um consultor específico.");
        }
        ConsultantResponse created = service.create(request.name(), "CREATED_IN_PORTAL", principal.username());
        portalUserService.ensureConsultantAccount(created.id());
        return created;
    }

    @PostMapping("/{id}/portal-login")
    public ConsultantResponse registerPortalLogin(@PathVariable UUID id, Authentication auth) {
        PortalPrincipal principal = principal(auth);
        portalUserService.assertConsultantAccess(principal.username(), principal.role(), id);
        return service.registerPortalLogin(id);
    }

    @PatchMapping("/{id}/whatsapp")
    public ConsultantResponse updateWhatsapp(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateConsultantWhatsappRequest request,
            Authentication auth
    ) {
        PortalPrincipal principal = principal(auth);
        portalUserService.assertConsultantAccess(principal.username(), principal.role(), id);
        service.findActiveConsultant(id);
        return service.update(id, null, null, null, request.whatsapp(), principal.username());
    }

    private PortalPrincipal principal(Authentication auth) {
        return (PortalPrincipal) auth.getPrincipal();
    }
}
