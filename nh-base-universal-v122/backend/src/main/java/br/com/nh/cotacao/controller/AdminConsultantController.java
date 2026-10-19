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
@RequestMapping("/api/admin/consultants")
public class AdminConsultantController {
    private final ConsultantService service;
    private final PortalUserService portalUserService;

    public AdminConsultantController(ConsultantService service, PortalUserService portalUserService) { this.service = service; this.portalUserService = portalUserService; }

    @GetMapping
    public List<ConsultantResponse> all() { return service.all(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultantResponse create(@Valid @RequestBody CreateConsultantRequest request, Authentication auth) {
        ConsultantResponse created = service.create(request.name(), request.role(), request.whatsapp(), request.city(), request.assignedAnalystId(), "ADMIN", username(auth));
        if (created.role() == br.com.nh.cotacao.entity.CollaboratorRole.CONSULTANT) portalUserService.ensureConsultantAccount(created.id());
        return created;
    }

    @PatchMapping("/{id}")
    public ConsultantResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateConsultantRequest request,
            Authentication auth
    ) {
        return service.update(id, request.name(), request.active(), request.role(), request.whatsapp(), request.city(), request.assignedAnalystId(), username(auth));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.delete(id, username(auth));
    }

    private String username(Authentication auth) {
        return ((PortalPrincipal) auth.getPrincipal()).username();
    }
}
