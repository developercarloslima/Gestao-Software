package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.EventAcceptanceDtos.EventAcceptanceInfo;
import br.com.nh.cotacao.dto.InspectionDtos.DeviceMetadata;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnAssertionOptionsResponse;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationFinishRequest;
import br.com.nh.cotacao.dto.InspectionDtos.WebAuthnRegistrationOptionsResponse;
import br.com.nh.cotacao.service.EventDigitalAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
public class EventDigitalAcceptanceController {
    private final EventDigitalAcceptanceService service;

    public EventDigitalAcceptanceController(EventDigitalAcceptanceService service) { this.service = service; }

    @GetMapping({"/api/workshop/events/{eventId}/acceptance","/api/checklist/events/{eventId}/acceptance"})
    public EventAcceptanceInfo internalStatus(@PathVariable UUID eventId) {
        return service.statusByEvent(eventId);
    }

    @PostMapping({"/api/workshop/events/{eventId}/acceptance/prepare","/api/checklist/events/{eventId}/acceptance/prepare"})
    public EventAcceptanceInfo prepare(@PathVariable UUID eventId) {
        return service.prepare(eventId);
    }

    @GetMapping("/api/public/events/acceptance/{token}")
    public EventAcceptanceInfo publicStatus(@PathVariable String token) {
        return service.publicStatus(token);
    }

    @GetMapping(value="/api/public/events/acceptance/{token}/dossier.pdf", produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> publicDossier(@PathVariable String token) {
        EventAcceptanceInfo info = service.publicStatus(token);
        byte[] pdf = service.publicDossier(token);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("dossie-associado-" + info.protocol().toLowerCase() + ".pdf", StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }

    @PostMapping("/api/public/events/acceptance/{token}/registration-options")
    public WebAuthnRegistrationOptionsResponse registrationOptions(@PathVariable String token,
                                                                    @Valid @RequestBody DeviceMetadata device,
                                                                    HttpServletRequest request) {
        return service.beginRegistration(token,device,request);
    }

    @PostMapping("/api/public/events/acceptance/{token}/registration-finish")
    public WebAuthnAssertionOptionsResponse registrationFinish(@PathVariable String token,
                                                                @Valid @RequestBody WebAuthnRegistrationFinishRequest body) {
        return service.finishRegistration(token,body);
    }

    @PostMapping("/api/public/events/acceptance/{token}/assertion-options")
    public WebAuthnAssertionOptionsResponse assertionOptions(@PathVariable String token) {
        return service.assertionOptions(token);
    }

    @PostMapping("/api/public/events/acceptance/{token}/assertion-finish")
    public EventAcceptanceInfo assertionFinish(@PathVariable String token,
                                                @Valid @RequestBody WebAuthnAssertionFinishRequest body) {
        return service.finishAssertion(token,body);
    }
}
