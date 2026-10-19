package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.ProcurementDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.ProcurementService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/procurement")
public class ProcurementController {
    private final ProcurementService service;

    public ProcurementController(ProcurementService service) { this.service = service; }

    @GetMapping("/events")
    public List<PurchaseEventSummary> list(@RequestParam(required=false) String q, Authentication auth) {
        return service.list(principal(auth), q);
    }

    @GetMapping("/events/{id}")
    public PurchaseEventDetail detail(@PathVariable UUID id, Authentication auth) {
        return service.detail(id, principal(auth));
    }

    @PatchMapping("/events/{eventId}/purchases/{purchaseId}")
    public PurchaseEventDetail update(
            @PathVariable UUID eventId,
            @PathVariable UUID purchaseId,
            @Valid @RequestBody UpdatePurchaseRequest request,
            Authentication auth
    ) {
        return service.updatePurchase(eventId, purchaseId, request, principal(auth));
    }

    private PortalPrincipal principal(Authentication auth) { return (PortalPrincipal) auth.getPrincipal(); }
}
