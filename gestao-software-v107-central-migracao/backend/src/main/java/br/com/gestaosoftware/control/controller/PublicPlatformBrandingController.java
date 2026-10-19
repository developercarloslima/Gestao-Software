package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.service.PlatformManagementService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/platform")
public class PublicPlatformBrandingController {
    private final PlatformManagementService service;

    public PublicPlatformBrandingController(PlatformManagementService service) {
        this.service = service;
    }

    @GetMapping("/branding")
    public Map<String, Object> branding(@RequestParam(required = false) String host) {
        return service.publicBranding(host);
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> publicFile(@PathVariable UUID fileId) {
        PlatformManagementService.FilePayload file = service.getCompanyFile(fileId, true);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.originalName().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.fileSize())
                .body(file.content());
    }

    @GetMapping("/contracts/{token}")
    public Map<String, Object> contract(@PathVariable UUID token) {
        return service.publicContract(token);
    }

    @PostMapping("/contracts/{token}/sign")
    public Map<String, Object> signContract(@PathVariable UUID token, @RequestBody PlatformManagementService.ClientContractSignature input) {
        return service.signClientContract(token, input);
    }
}
