package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.AdminDtos.AdminInspectionResponse;
import br.com.nh.cotacao.dto.AdminDtos.InspectionPlanOption;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionPlanRequest;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewRequest;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionStatusRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionDetailsRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionContractValuesRequest;
import br.com.nh.cotacao.dto.PortalDtos.ConsultantResponse;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.AdminActivityService;
import br.com.nh.cotacao.service.ConsultantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis/inspections")
public class AnalysisInspectionController {
    private final AdminActivityService service;
    private final ConsultantService consultantService;

    public AnalysisInspectionController(AdminActivityService service, ConsultantService consultantService) {
        this.service = service;
        this.consultantService = consultantService;
    }

    @GetMapping
    public List<AdminInspectionResponse> list(Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.inspectionsForAnalysis(principal.username(), principal.role());
    }

    @GetMapping("/analysts")
    public List<ConsultantResponse> analysts() { return consultantService.activeAnalysts(); }

    @PatchMapping("/{id}/details")
    public AdminInspectionResponse updateDetails(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionDetailsRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.updateInspectionDetails(id, request, principal.username(), principal.role());
    }

    @GetMapping("/{id}/plans")
    public List<InspectionPlanOption> availablePlans(@PathVariable UUID id, Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.plansForInspection(id, principal.username(), principal.role());
    }

    @PatchMapping("/{id}/plan")
    public AdminInspectionResponse updatePlan(
            @PathVariable UUID id, @Valid @RequestBody UpdateInspectionPlanRequest request, Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.updateInspectionPlan(id, request, principal.username(), principal.role());
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

    @PostMapping("/{id}/registration-complete")
    public AdminInspectionResponse registrationComplete(
            @PathVariable UUID id,
            @Valid @RequestBody RegistrationCompleteRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.markRegistrationCompleted(id, request.note(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/registration-not-complete")
    public AdminInspectionResponse registrationNotComplete(
            @PathVariable UUID id,
            @Valid @RequestBody RegistrationCompleteRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.markRegistrationNotCompleted(id, request.note(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/decision-message-sent")
    public AdminInspectionResponse markDecisionMessageSent(@PathVariable UUID id, Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.markDecisionMessageSentForAnalysis(id, principal.username(), principal.role());
    }

    @PatchMapping("/{id}/status")
    public AdminInspectionResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionStatusRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.updateInspectionStatusForAnalysis(id, request, principal.username(), principal.role());
    }

    public record RegistrationCompleteRequest(@Size(max = 1200) String note) {}
}
