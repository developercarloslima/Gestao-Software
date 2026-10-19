package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.ChecklistEventDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.ChecklistEventPdfService;
import br.com.nh.cotacao.service.ChecklistEventService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/checklist")
public class ChecklistEventController {
    private final ChecklistEventService service;
    private final ChecklistEventPdfService pdfService;

    public ChecklistEventController(ChecklistEventService service, ChecklistEventPdfService pdfService) {
        this.service = service;
        this.pdfService = pdfService;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        return service.meta();
    }


    @GetMapping("/tow-by-plate")
    public TowLookupResponse towByPlate(@RequestParam String plate, Authentication auth) {
        return service.towByPlate(plate, principal(auth));
    }

    @GetMapping("/events")
    public List<EventSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            Authentication auth
    ) {
        return service.list(principal(auth), q, status);
    }

    @PostMapping("/events")
    public EventDetail create(@Valid @RequestBody CreateEventRequest request, Authentication auth) {
        return service.create(request, principal(auth));
    }

    @GetMapping("/events/{id}")
    public EventDetail detail(@PathVariable UUID id, Authentication auth) {
        return service.detail(id, principal(auth));
    }

    @PatchMapping("/events/{id}")
    public EventDetail update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEventRequest request,
            Authentication auth
    ) {
        return service.update(id, request, principal(auth));
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable UUID id, Authentication auth) {
        service.deleteEvent(id, principal(auth));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/events/{eventId}/checklist/{itemId}")
    public EventDetail updateChecklistItem(
            @PathVariable UUID eventId,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateChecklistItemRequest request,
            Authentication auth
    ) {
        return service.updateChecklistItem(eventId, itemId, request, principal(auth));
    }

    @PatchMapping("/events/{eventId}/tow-checklist/{itemId}")
    public EventDetail updateTowItem(
            @PathVariable UUID eventId,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateTowItemRequest request,
            Authentication auth
    ) {
        return service.updateTowItem(eventId, itemId, request, principal(auth));
    }

    @PostMapping("/events/{eventId}/third-parties")
    public EventDetail addThirdParty(
            @PathVariable UUID eventId,
            @Valid @RequestBody ThirdPartyCreateRequest request,
            Authentication auth
    ) {
        return service.addThirdParty(eventId, request, principal(auth));
    }

    @DeleteMapping("/events/{eventId}/third-parties/{thirdPartyId}")
    public EventDetail deleteThirdParty(
            @PathVariable UUID eventId,
            @PathVariable UUID thirdPartyId,
            Authentication auth
    ) {
        return service.deleteThirdParty(eventId, thirdPartyId, principal(auth));
    }

    @PostMapping(value = "/events/{eventId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EventDetail upload(
            @PathVariable UUID eventId,
            @RequestParam String context,
            @RequestParam String kind,
            @RequestParam(required = false) UUID thirdPartyId,
            @RequestParam(required = false) UUID checklistItemId,
            @RequestParam(required = false) String notes,
            @RequestParam("files") List<MultipartFile> files,
            Authentication auth
    ) {
        return service.upload(eventId, thirdPartyId, checklistItemId, context, kind, notes, files, principal(auth));
    }

    @GetMapping("/events/{eventId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> attachment(
            @PathVariable UUID eventId,
            @PathVariable UUID attachmentId,
            Authentication auth
    ) {
        AttachmentContent content = service.attachment(eventId, attachmentId, principal(auth));
        MediaType type;
        try {
            type = MediaType.parseMediaType(content.contentType());
        } catch (Exception ignored) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(content.bytes());
    }

    @DeleteMapping("/events/{eventId}/attachments/{attachmentId}")
    public EventDetail deleteAttachment(
            @PathVariable UUID eventId,
            @PathVariable UUID attachmentId,
            Authentication auth
    ) {
        return service.deleteAttachment(eventId, attachmentId, principal(auth));
    }

    @PostMapping("/events/{eventId}/complete-registration")
    public EventDetail completeRegistration(@PathVariable UUID eventId, Authentication auth) {
        return service.completeRegistration(eventId, principal(auth));
    }

    @PatchMapping("/events/{eventId}/review-status")
    public EventDetail reviewStatus(
            @PathVariable UUID eventId,
            @Valid @RequestBody ReviewStatusRequest request,
            Authentication auth
    ) {
        return service.reviewStatus(eventId, request, principal(auth));
    }

    @GetMapping(value = "/events/{eventId}/dossier.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> dossier(@PathVariable UUID eventId, Authentication auth) {
        PortalPrincipal principal = principal(auth);
        EventDetail event = service.detail(eventId, principal);
        byte[] pdf = pdfService.generate(event, service.imageAttachments(eventId, principal));
        String fileName = "dossie-" + event.protocol().toLowerCase() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }

    @GetMapping(value = "/events/{eventId}/internal-dossier.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> internalDossier(@PathVariable UUID eventId, Authentication auth) {
        PortalPrincipal principal = principal(auth);
        EventDetail event = service.detail(eventId, principal);
        byte[] pdf = pdfService.generateInternal(event, service.imageAttachments(eventId, principal));
        String fileName = "dossie-interno-" + event.protocol().toLowerCase() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(pdf);
    }

    private PortalPrincipal principal(Authentication auth) {
        return (PortalPrincipal) auth.getPrincipal();
    }
}
