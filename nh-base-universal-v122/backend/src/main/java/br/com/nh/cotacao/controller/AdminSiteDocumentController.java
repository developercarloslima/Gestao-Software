package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.SiteDocumentService;
import br.com.nh.cotacao.service.SiteDocumentService.DocumentMetadata;
import br.com.nh.cotacao.service.SiteDocumentService.StoredDocument;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/admin/settings")
public class AdminSiteDocumentController {
    private final SiteDocumentService service;

    public AdminSiteDocumentController(SiteDocumentService service) {
        this.service = service;
    }

    @GetMapping("/regulation")
    public DocumentMetadata regulation() {
        return service.regulationMetadata();
    }

    @PutMapping(value = "/regulation", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentMetadata updateRegulation(@RequestPart("file") MultipartFile file, Authentication auth) {
        return service.updateRegulation(file, username(auth));
    }

    @GetMapping("/report-cover")
    public DocumentMetadata reportCover() { return service.reportCoverMetadata(); }

    @PutMapping(value = "/report-cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentMetadata updateReportCover(@RequestPart("file") MultipartFile file, Authentication auth) {
        return service.updateReportCover(file, username(auth));
    }

    @DeleteMapping("/report-cover")
    public DocumentMetadata removeReportCover(Authentication auth) {
        return service.removeReportCover(username(auth));
    }

    @GetMapping(value = "/report-cover/file", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportCoverFile() { return fileResponse(service.reportCoverFile(), "capa-relatorios.pdf"); }

    @GetMapping("/report-ending")
    public DocumentMetadata reportEnding() { return service.reportEndingMetadata(); }

    @PutMapping(value = "/report-ending", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentMetadata updateReportEnding(@RequestPart("file") MultipartFile file, Authentication auth) {
        return service.updateReportEnding(file, username(auth));
    }

    @DeleteMapping("/report-ending")
    public DocumentMetadata removeReportEnding(Authentication auth) {
        return service.removeReportEnding(username(auth));
    }

    @GetMapping(value = "/report-ending/file", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> reportEndingFile() { return fileResponse(service.reportEndingFile(), "pagina-final-relatorios.pdf"); }

    private String username(Authentication auth) {
        return ((PortalPrincipal) auth.getPrincipal()).username();
    }

    private ResponseEntity<byte[]> fileResponse(StoredDocument document, String fallbackName) {
        if (document == null || document.bytes() == null || document.bytes().length == 0) return ResponseEntity.notFound().build();
        String fileName = document.fileName() == null || document.fileName().isBlank() ? fallbackName : document.fileName();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(document.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(document.bytes());
    }
}
