package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.WorkshopPortalDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.WorkshopPortalService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/workshop")
public class WorkshopPortalController {
    private final WorkshopPortalService service;

    public WorkshopPortalController(WorkshopPortalService service) { this.service = service; }

    @GetMapping("/events")
    public List<WorkshopQueueSummary> list(@RequestParam(required=false) String q,
                                           @RequestParam(required=false) String status,
                                           Authentication auth) {
        return service.list(principal(auth),q,status);
    }

    @GetMapping("/events/{id}")
    public WorkshopEventDetail detail(@PathVariable UUID id, Authentication auth) {
        return service.detail(id,principal(auth));
    }

    @PatchMapping("/events/{eventId}/items/{itemId}")
    public WorkshopEventDetail updateItem(@PathVariable UUID eventId,@PathVariable UUID itemId,
                                          @Valid @RequestBody UpdateWorkshopItemRequest request,Authentication auth) {
        return service.updateItem(eventId,itemId,request,principal(auth));
    }

    @PostMapping("/events/{eventId}/items/custom")
    public WorkshopEventDetail addCustomItem(@PathVariable UUID eventId,
                                              @Valid @RequestBody AddCustomWorkshopItemRequest request,
                                              Authentication auth) {
        return service.addCustomItem(eventId, request, principal(auth));
    }

    @PostMapping("/events/{eventId}/mark-no-damage")
    public WorkshopEventDetail markPendingNoDamage(@PathVariable UUID eventId,
                                                     @RequestBody(required=false) BulkNoDamageRequest request,
                                                     Authentication auth) {
        return service.markPendingNoDamage(eventId, request, principal(auth));
    }

    @PostMapping("/events/{eventId}/complete")
    public WorkshopEventDetail complete(@PathVariable UUID eventId,Authentication auth) {
        return service.complete(eventId,principal(auth));
    }

    private PortalPrincipal principal(Authentication auth) { return (PortalPrincipal)auth.getPrincipal(); }
}
