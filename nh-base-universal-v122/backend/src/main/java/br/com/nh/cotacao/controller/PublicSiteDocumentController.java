package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.SiteDocumentService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class PublicSiteDocumentController {
    private final SiteDocumentService service;

    public PublicSiteDocumentController(SiteDocumentService service) {
        this.service = service;
    }

    @GetMapping("/api/public/documents/regulation")
    public ResponseEntity<byte[]> regulation() {
        SiteDocumentService.StoredDocument document = service.regulationFile();
        String disposition = ContentDisposition.attachment()
                .filename(document.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(document.fileSize())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(document.bytes());
    }
}
