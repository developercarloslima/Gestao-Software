package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.ConsultantDashboardDtos.ConsultantDashboardResponse;
import br.com.nh.cotacao.dto.ConsultantDashboardDtos.ConsultantInspectionSummary;
import br.com.nh.cotacao.dto.ConsultantDashboardDtos.ConsultantQuoteSummary;
import br.com.nh.cotacao.dto.ConsultantDashboardDtos.ConsultantQuoteDecisionRequest;
import br.com.nh.cotacao.dto.ConsultantDashboardDtos.RedoQuoteRequest;
import br.com.nh.cotacao.dto.ConsultantDashboardDtos.StartInspectionRequest;
import br.com.nh.cotacao.dto.ConsultantDashboardDtos.UpdateQuoteDetailsRequest;
import br.com.nh.cotacao.dto.AdminDtos.AdminInspectionResponse;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewRequest;
import br.com.nh.cotacao.dto.AdminDtos.CommercialPricingPreviewResponse;
import br.com.nh.cotacao.dto.AdminDtos.UpdateInspectionContractValuesRequest;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.ConsultantDashboardService;
import br.com.nh.cotacao.service.AdminActivityService;
import br.com.nh.cotacao.service.PortalUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/consultant-dashboard")
public class ConsultantDashboardController {
    private final ConsultantDashboardService service;
    private final PortalUserService portalUserService;
    private final AdminActivityService adminActivityService;

    public ConsultantDashboardController(
            ConsultantDashboardService service,
            PortalUserService portalUserService,
            AdminActivityService adminActivityService
    ) {
        this.service = service;
        this.portalUserService = portalUserService;
        this.adminActivityService = adminActivityService;
    }

    @GetMapping("/{consultantId}")
    public ConsultantDashboardResponse dashboard(@PathVariable UUID consultantId, Authentication auth) {
        assertConsultant(auth, consultantId);
        return service.dashboard(consultantId);
    }

    @GetMapping("/{consultantId}/inspections/{inspectionId}/commercial-edit")
    public AdminInspectionResponse commercialEdit(
            @PathVariable UUID consultantId,
            @PathVariable UUID inspectionId,
            Authentication auth
    ) {
        assertConsultant(auth, consultantId);
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return adminActivityService.inspectionForContractEdit(
                inspectionId, principal.username(), principal.role()
        );
    }

    @PostMapping("/{consultantId}/inspections/{inspectionId}/contract-pricing-preview")
    public CommercialPricingPreviewResponse previewContractPricing(
            @PathVariable UUID consultantId,
            @PathVariable UUID inspectionId,
            @Valid @RequestBody CommercialPricingPreviewRequest request,
            Authentication auth
    ) {
        assertConsultant(auth, consultantId);
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return adminActivityService.previewInspectionContractPricing(
                inspectionId, request, principal.username(), principal.role()
        );
    }

    @PatchMapping("/{consultantId}/inspections/{inspectionId}/contract-values")
    public AdminInspectionResponse updateContractValues(
            @PathVariable UUID consultantId,
            @PathVariable UUID inspectionId,
            @Valid @RequestBody UpdateInspectionContractValuesRequest request,
            Authentication auth
    ) {
        assertConsultant(auth, consultantId);
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        return adminActivityService.updateInspectionContractValues(
                inspectionId, request, principal.username(), principal.role()
        );
    }

    @PostMapping("/{consultantId}/quotes/{quoteId}/inspection")
    public ConsultantInspectionSummary ensureInspection(
            @PathVariable UUID consultantId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody(required = false) StartInspectionRequest request,
            Authentication auth
    ) {
        assertConsultant(auth, consultantId);
        return service.ensureInspection(consultantId, quoteId, request == null ? null : request.cpf());
    }

    @PostMapping("/{consultantId}/quotes/{quoteId}/redo")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultantQuoteSummary redoQuote(
            @PathVariable UUID consultantId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody(required = false) RedoQuoteRequest request,
            Authentication auth
    ) {
        assertConsultant(auth, consultantId);
        return service.redoQuote(consultantId, quoteId, request == null ? null : request.cpf());
    }

    @PatchMapping("/{consultantId}/quotes/{quoteId}")
    public ConsultantQuoteSummary updateQuoteDetails(
            @PathVariable UUID consultantId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody UpdateQuoteDetailsRequest request,
            Authentication auth
    ) {
        assertConsultant(auth, consultantId);
        return service.updateQuoteDetails(consultantId, quoteId, request);
    }

    @PostMapping("/{consultantId}/quotes/{quoteId}/decision")
    public ConsultantQuoteSummary decideQuote(
            @PathVariable UUID consultantId,
            @PathVariable UUID quoteId,
            @Valid @RequestBody ConsultantQuoteDecisionRequest request,
            Authentication auth
    ) {
        assertConsultant(auth, consultantId);
        return service.decideQuote(consultantId, quoteId, request.decision());
    }

    @DeleteMapping("/{consultantId}/quotes/{quoteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuote(@PathVariable UUID consultantId, @PathVariable UUID quoteId, Authentication auth) {
        assertConsultant(auth, consultantId);
        service.deleteQuote(consultantId, quoteId);
    }

    @PostMapping("/inspections/{inspectionId}/completion-message-sent")
    public ConsultantInspectionSummary markCompletionMessageSent(@PathVariable UUID inspectionId, Authentication auth) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        portalUserService.assertInspectionAccess(principal.username(), principal.role(), inspectionId);
        return service.markCompletionMessageSent(inspectionId);
    }

    private void assertConsultant(Authentication auth, UUID consultantId) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        portalUserService.assertConsultantAccess(principal.username(), principal.role(), consultantId);
    }
}
