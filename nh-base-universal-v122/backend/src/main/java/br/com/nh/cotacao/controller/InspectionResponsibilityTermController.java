package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.InspectionDtos.DeviceMetadata;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionOptionsResponse;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationOptionsResponse;
import br.com.nh.cotacao.dto.ResponsibilityTermDtos.PrepareResponsibilityTermRequest;
import br.com.nh.cotacao.dto.ResponsibilityTermDtos.ResponsibilityTermInfo;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.InspectionResponsibilityTermService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
public class InspectionResponsibilityTermController {
    private final InspectionResponsibilityTermService service;

    public InspectionResponsibilityTermController(InspectionResponsibilityTermService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/inspections/{inspectionId}/responsibility-term/prepare")
    public ResponsibilityTermInfo prepare(
            @PathVariable UUID inspectionId,
            @Valid @RequestBody(required = false) PrepareResponsibilityTermRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.prepare(inspectionId, principal.username(), request == null ? null : request.damageDetails());
    }

    @GetMapping("/api/admin/inspections/{inspectionId}/responsibility-term")
    public ResponseEntity<ResponsibilityTermInfo> adminStatus(@PathVariable UUID inspectionId) {
        ResponsibilityTermInfo info = service.statusByInspection(inspectionId);
        return info == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(info);
    }

    @PostMapping("/api/admin/inspections/{inspectionId}/responsibility-term/message-sent")
    public ResponsibilityTermInfo markSent(@PathVariable UUID inspectionId) {
        return service.markSent(inspectionId);
    }

    @PostMapping("/api/analysis/inspections/{inspectionId}/responsibility-term/prepare")
    public ResponsibilityTermInfo prepareForAnalysis(
            @PathVariable UUID inspectionId,
            @Valid @RequestBody(required = false) PrepareResponsibilityTermRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.prepare(inspectionId, principal.username(), request == null ? null : request.damageDetails());
    }

    @GetMapping("/api/analysis/inspections/{inspectionId}/responsibility-term")
    public ResponseEntity<ResponsibilityTermInfo> analysisStatus(@PathVariable UUID inspectionId) {
        ResponsibilityTermInfo info = service.statusByInspection(inspectionId);
        return info == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(info);
    }

    @PostMapping("/api/analysis/inspections/{inspectionId}/responsibility-term/message-sent")
    public ResponsibilityTermInfo markSentForAnalysis(@PathVariable UUID inspectionId) {
        return service.markSent(inspectionId);
    }

    @PostMapping("/api/supervision/inspections/{inspectionId}/responsibility-term/prepare")
    public ResponsibilityTermInfo prepareForSupervision(
            @PathVariable UUID inspectionId,
            @Valid @RequestBody(required = false) PrepareResponsibilityTermRequest request,
            Authentication authentication
    ) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        return service.prepare(inspectionId, principal.username(), request == null ? null : request.damageDetails());
    }

    @GetMapping("/api/supervision/inspections/{inspectionId}/responsibility-term")
    public ResponseEntity<ResponsibilityTermInfo> supervisionStatus(@PathVariable UUID inspectionId) {
        ResponsibilityTermInfo info = service.statusByInspection(inspectionId);
        return info == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(info);
    }

    @PostMapping("/api/supervision/inspections/{inspectionId}/responsibility-term/message-sent")
    public ResponsibilityTermInfo markSentForSupervision(@PathVariable UUID inspectionId) {
        return service.markSent(inspectionId);
    }

    @GetMapping("/api/public/responsibility-terms/{token}")
    public ResponsibilityTermInfo publicStatus(@PathVariable String token) {
        return service.publicStatus(token);
    }

    @GetMapping(value = "/api/public/responsibility-terms/{token}/term.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> publicPdf(@PathVariable String token) {
        ResponsibilityTermInfo info = service.publicStatus(token);
        byte[] pdf = service.publicPdf(token);
        String plate = info.vehiclePlate() == null || info.vehiclePlate().isBlank() ? "sem-placa" : info.vehiclePlate().toLowerCase();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("termo-responsabilidade-" + plate + ".pdf", StandardCharsets.UTF_8)
                        .build().toString())
                .body(pdf);
    }

    @PostMapping("/api/public/responsibility-terms/{token}/registration-options")
    public WebAuthnRegistrationOptionsResponse registrationOptions(
            @PathVariable String token,
            @Valid @RequestBody DeviceMetadata device,
            HttpServletRequest request
    ) {
        return service.beginRegistration(token, device, request);
    }

    @PostMapping("/api/public/responsibility-terms/{token}/registration-finish")
    public WebAuthnAssertionOptionsResponse registrationFinish(
            @PathVariable String token,
            @Valid @RequestBody WebAuthnRegistrationFinishRequest request
    ) {
        return service.finishRegistration(token, request);
    }

    @PostMapping("/api/public/responsibility-terms/{token}/assertion-options")
    public WebAuthnAssertionOptionsResponse assertionOptions(@PathVariable String token) {
        return service.assertionOptions(token);
    }

    @PostMapping("/api/public/responsibility-terms/{token}/assertion-finish")
    public ResponsibilityTermInfo assertionFinish(
            @PathVariable String token,
            @Valid @RequestBody WebAuthnAssertionFinishRequest request
    ) {
        return service.finishAssertion(token, request);
    }
}
