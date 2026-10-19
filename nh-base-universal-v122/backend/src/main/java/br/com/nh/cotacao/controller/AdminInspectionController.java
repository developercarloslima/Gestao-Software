package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.AdminDtos.AdminInspectionResponse;
import br.com.nh.cotacao.dto.AdminDtos.InspectionPlanOption;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionPlanRequest;
import br.com.nh.cotacao.dto.PortalDtos.ConsultantResponse;
import br.com.nh.cotacao.service.ConsultantService;
import jakarta.validation.constraints.NotNull;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewRequest;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewResponse;
import br.com.nh.cotacao.dto.AdminDtos.DeleteSummary;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionStatusRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionDetailsRequest;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionContractValuesRequest;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.AdminActivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/inspections")
public class AdminInspectionController {
    private final AdminActivityService service;
    private final ConsultantService consultantService;
    private final String bulkDeletePassword;

    public AdminInspectionController(
            AdminActivityService service,
            ConsultantService consultantService,
            @Value("${app.admin.bulk-delete-password:}") String bulkDeletePassword
    ) {
        this.service = service;
        this.consultantService = consultantService;
        this.bulkDeletePassword = bulkDeletePassword;
    }

    @GetMapping
    public List<AdminInspectionResponse> list() { return service.inspections(); }

    @PatchMapping("/{id}/details")
    public AdminInspectionResponse updateDetails(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionDetailsRequest request,
            Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.updateInspectionDetails(id, request, principal.username(), principal.role());
    }

    // O administrador opera plano e redistribuição sem sair do painel ADM.
    @GetMapping("/{id}/plans")
    public List<InspectionPlanOption> plans(@PathVariable UUID id, Authentication auth) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.plansForInspection(id, principal.username(), principal.role());
    }

    @PatchMapping("/{id}/plan")
    public AdminInspectionResponse updatePlan(@PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionPlanRequest request, Authentication auth) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.updateInspectionPlan(id, request, principal.username(), principal.role());
    }

    @GetMapping("/analysts")
    public List<ConsultantResponse> analysts() { return consultantService.activeAnalysts(); }

    @PatchMapping("/{id}/analyst")
    public AdminInspectionResponse assignAnalyst(@PathVariable UUID id,
            @Valid @RequestBody AssignAnalystRequest request, Authentication auth) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.reassignInspectionAnalyst(id, request.analystId(), principal.username(), principal.role());
    }

    public record AssignAnalystRequest(@NotNull UUID analystId) {}

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

    @PatchMapping("/{id}/status")
    public AdminInspectionResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionStatusRequest request,
            Authentication auth
    ) {
        return service.updateInspectionStatus(id, request, username(auth));
    }


    @PostMapping("/{id}/registration-complete")
    public AdminInspectionResponse registrationComplete(
            @PathVariable UUID id,
            @Valid @RequestBody AdminInspectionNoteRequest request,
            Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.markRegistrationCompleted(id, request.note(), principal.username(), principal.role());
    }

    @PostMapping("/{id}/registration-not-complete")
    public AdminInspectionResponse registrationNotComplete(
            @PathVariable UUID id,
            @Valid @RequestBody AdminInspectionNoteRequest request,
            Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.markRegistrationNotCompleted(id, request.note(), principal.username(), principal.role());
    }

    @PatchMapping("/{id}/supervision-note")
    public AdminInspectionResponse updateSupervisionNote(
            @PathVariable UUID id,
            @Valid @RequestBody AdminInspectionNoteRequest request,
            Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.updateSupervisionNote(id, request.note(), principal.username(), principal.role());
    }

    @PatchMapping("/{id}/supervision-status")
    public AdminInspectionResponse updateSupervisionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInspectionStatusRequest request,
            Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.updateInspectionStatusForSupervision(id, request, principal.username(), principal.role());
    }

    @PostMapping("/{id}/decision-message-sent")
    public AdminInspectionResponse markDecisionMessageSent(@PathVariable UUID id, Authentication auth) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return service.markDecisionMessageSentForSupervision(id, principal.username(), principal.role());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.deleteInspection(id, username(auth));
    }

    @DeleteMapping
    public DeleteSummary deleteAllAllowed(
            @RequestHeader(value = "X-NH-Delete-Password", required = false) String confirmationPassword,
            Authentication auth
    ) {
        byte[] expected = bulkDeletePassword == null ? new byte[0] : bulkDeletePassword.getBytes(StandardCharsets.UTF_8);
        byte[] provided = confirmationPassword == null ? new byte[0] : confirmationPassword.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new IllegalArgumentException("Senha de confirmação inválida. A exclusão total não foi executada.");
        }
        return service.deleteAllAllowedInspections(username(auth));
    }

    public record AdminInspectionNoteRequest(@Size(max = 1200) String note) {}

    private String username(Authentication auth) {
        return ((PortalPrincipal) auth.getPrincipal()).username();
    }
}
