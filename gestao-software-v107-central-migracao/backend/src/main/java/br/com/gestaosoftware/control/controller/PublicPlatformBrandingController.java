package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.service.PlatformManagementService;
import br.com.gestaosoftware.control.service.CompanySiteEditorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/platform")
public class PublicPlatformBrandingController {
    private final PlatformManagementService service;
    private final CompanySiteEditorService siteEditor;

    public PublicPlatformBrandingController(PlatformManagementService service,CompanySiteEditorService siteEditor) {
        this.service = service;
        this.siteEditor = siteEditor;
    }

    @GetMapping("/branding")
    public Map<String, Object> branding(@RequestParam(required = false) String host) {
        Map<String,Object> identity=service.publicBranding(host);
        Map<String,Object> result=new LinkedHashMap<>(identity);
        if(result.get("companyId") instanceof UUID id
                && Boolean.TRUE.equals(result.get("active"))
                && result.get("enabledModules") instanceof List<?> list
                && list.contains("SITE_PRINCIPAL")) {
            result.put("siteContent",siteEditor.get(id));
        }
        return result;
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
