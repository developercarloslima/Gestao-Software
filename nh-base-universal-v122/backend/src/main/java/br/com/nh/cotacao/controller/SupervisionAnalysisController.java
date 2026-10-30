package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.AdminDtos.AdminInspectionResponse;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewRequest;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionStatusRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionDetailsRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionContractValuesRequest;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.AdminActivityService;
import br.com.nh.cotacao.service.ConsultantService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/supervision/inspections")
public class SupervisionAnalysisController {
    private final AdminActivityService service;
    private final ConsultantService consultantService;
    private final br.com.nh.cotacao.service.InspectionAcceptanceWorkflowService acceptanceWorkflow;

    public SupervisionAnalysisController(AdminActivityService service, ConsultantService consultantService,
            br.com.nh.cotacao.service.InspectionAcceptanceWorkflowService acceptanceWorkflow) {
        this.service = service;
        this.consultantService = consultantService;
        this.acceptanceWorkflow = acceptanceWorkflow;
    }

    @GetMapping("/analysts")
    public List<br.com.nh.cotacao.dto.PortalDtos.ConsultantResponse> analysts() {
        return consultantService.activeAnalysts();
    }

    @GetMapping
    public List<AdminInspectionResponse> list() {
        return service.inspectionsForSupervision();
    }

    @PatchMapping("/{id}/details")
    public AdminInspectionResponse updateDetails(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionDetailsRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.updateInspectionDetails(id, request, principal.username(), principal.role());
    }

    @PatchMapping("/{id}/contract-values")
    public AdminInspectionResponse updateContractValues(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionContractValuesRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.updateInspectionContractValues(id, request, principal.username(), principal.role());
    }

    @PostMapping("/{id}/contract-pricing-preview")
    public CommercialPricingPreviewResponse previewContractPricing(
            @PathVariable UUID id,
            @Valid @RequestBody CommercialPricingPreviewRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.previewInspectionContractPricing(id, request, principal.username(), principal.role());
    }

    @PatchMapping("/{id}/supervision-note")
    public AdminInspectionResponse updateSupervisionNote(
            @PathVariable UUID id,
            @Valid @RequestBody SupervisionNoteRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.updateSupervisionNote(id, request.note(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/registration-complete")
    public AdminInspectionResponse registrationComplete(
            @PathVariable UUID id,
            @Valid @RequestBody SupervisionRegistrationRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.markRegistrationCompleted(id, request.note(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/registration-not-complete")
    public AdminInspectionResponse registrationNotComplete(
            @PathVariable UUID id,
            @Valid @RequestBody SupervisionRegistrationRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.markRegistrationNotCompleted(id, request.note(), principal.username(), principal.role());
    }

    @PatchMapping("/{id}/status")
    public AdminInspectionResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionStatusRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.updateInspectionStatusForSupervision(id, request, principal.username(), principal.role());
    }

    @PostMapping("/{id}/decision-message-sent")
    public AdminInspectionResponse markDecisionMessageSent(@PathVariable UUID id, Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.markDecisionMessageSentForSupervision(id, principal.username(), principal.role());
    }

    @PatchMapping("/{id}/analyst")
    public AdminInspectionResponse reassignAnalyst(@PathVariable UUID id,
            @Valid @RequestBody AssignAnalystRequest request, Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.reassignInspectionAnalyst(id, request.analystId(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/acceptance/reapprove")
    public br.com.nh.cotacao.service.InspectionAcceptanceWorkflowService.Progress reapproveAcceptance(
            @PathVariable UUID id, @Valid @RequestBody AcceptanceReapprovalRequest request,
            Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return acceptanceWorkflow.renewAfterApproval(id, request.reason(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/acceptance/queue/mark-completed")
    public AdminInspectionResponse moveAcceptanceQueueCompleted(
            @PathVariable UUID id, @Valid @RequestBody AcceptanceQueueMoveRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.moveAcceptanceQueueManually(id, true, request.reason(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/acceptance/queue/undo")
    public AdminInspectionResponse undoAcceptanceQueueMove(
            @PathVariable UUID id, @Valid @RequestBody AcceptanceQueueMoveRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.moveAcceptanceQueueManually(id, false, request.reason(), principal.username(), principal.role());
    }

    public record AcceptanceQueueMoveRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(min=5,max=1200) String reason
    ) {}

    public record AcceptanceReapprovalRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1200) String reason) {}
    public record AssignAnalystRequest(@NotNull UUID analystId) {}
    public record SupervisionNoteRequest(@Size(max = 1200) String note) {}
    public record SupervisionRegistrationRequest(@Size(max = 1200) String note) {}
}
