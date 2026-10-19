package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.QuoteDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.PortalUserService;
import br.com.nh.cotacao.service.QuotePdfService;
import br.com.nh.cotacao.service.QuoteService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteService quoteService;
    private final QuotePdfService pdfService;
    private final PortalUserService portalUserService;

    public QuoteController(
            QuoteService quoteService,
            QuotePdfService pdfService,
            PortalUserService portalUserService
    ) {
        this.quoteService = quoteService;
        this.pdfService = pdfService;
        this.portalUserService = portalUserService;
    }

    @PostMapping("/options")
    public OptionsResponse options(@Valid @RequestBody OptionsRequest request) {
        return quoteService.options(request);
    }

    @PostMapping
    public ResponseEntity<QuoteResponse> create(
            @Valid @RequestBody CreateQuoteRequest request, Authentication auth
    ) {
        PortalPrincipal principal = (PortalPrincipal) auth.getPrincipal();
        portalUserService.assertConsultantAccess(principal.username(), principal.role(), request.consultantId());
        return ResponseEntity.status(201).body(quoteService.create(request));
    }

    @GetMapping("/{id}")
    public QuoteResponse get(@PathVariable UUID id) {
        return quoteService.get(id);
    }

    @PostMapping("/{id}/decision")
    public DecisionResponse decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return quoteService.decide(id, request);
    }

    /**
     * O fluxo antigo enviava fotos diretamente para o Google Drive. Ele fica
     * explicitamente desativado para que nenhuma vistoria contorne o Retrato NH
     * e a persistência obrigatória no PostgreSQL.
     */
    @PostMapping(value = "/{id}/inspection", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void legacyInspectionUploadDisabled(@PathVariable UUID id) {
        throw new ResponseStatusException(
                HttpStatus.GONE,
                "Este envio antigo foi desativado. Gere ou abra o link do Retrato NH para salvar os arquivos no PostgreSQL."
        );
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        var quote = quoteService.find(id);
        byte[] bytes = pdfService.generate(quote);
        String filename = "cotacao-" + quote.getQuoteNumber() + ".pdf";
        String disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(bytes.length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(bytes);
    }
}
