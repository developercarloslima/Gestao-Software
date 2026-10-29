package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.service.CompanySiteEditorService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@RestController
public class CompanySiteEditorController {
    private final CompanySiteEditorService service;
    public CompanySiteEditorController(CompanySiteEditorService service){this.service=service;}

    @GetMapping("/api/platform/companies/{id}/site-content")
    public Map<String,Object> get(@PathVariable UUID id){return service.get(id);}

    @PutMapping("/api/platform/companies/{id}/site-content")
    public Map<String,Object> save(@PathVariable UUID id,@RequestBody Map<String,Object> content){
        return service.save(id,content);
    }

    @PostMapping(value="/api/platform/companies/{id}/site-assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@PathVariable UUID id,@RequestParam("file") MultipartFile file) throws IOException {
        return service.upload(id,file);
    }

    @GetMapping("/api/public/platform/site-assets/{assetId}")
    public ResponseEntity<byte[]> asset(@PathVariable UUID assetId){
        var asset=service.publicAsset(assetId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(asset.contentType()))
            .cacheControl(CacheControl.maxAge(30,TimeUnit.DAYS).cachePublic())
            .body(asset.content());
    }
}
