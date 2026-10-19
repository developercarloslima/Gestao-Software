package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.SecureCustomerImportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Exclusivo do administrador autenticado do próprio tenant: SecurityConfig /api/admin/**. */
@RestController
@RequestMapping("/api/admin/importacao")
@ConditionalOnProperty(name="app.tenant.mode",havingValue="true")
public class SecureCustomerImportController {
    private final SecureCustomerImportService service;
    public SecureCustomerImportController(SecureCustomerImportService service){this.service=service;}
    @PostMapping(value="/preparar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public SecureCustomerImportService.ValidationPreview prepare(@RequestPart("arquivo") MultipartFile file,
      @RequestParam("autorizado") boolean authorized,Authentication authentication)throws IOException {
        if(file.isEmpty() || file.getSize()>2_000_000)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"CSV deve ter no máximo 2 MB.");
        return service.upload(file.getBytes(),file.getOriginalFilename(),authorized,authentication.getName());
    }
    public record Confirmation(boolean confirmo){}
    @PostMapping("/{id}/confirmar")
    public SecureCustomerImportService.ImportResult confirm(@PathVariable UUID id,@RequestBody Confirmation confirmation,Authentication authentication){
        return service.confirm(id,confirmation!=null && confirmation.confirmo(),authentication.getName());
    }
    @GetMapping("/clientes") public java.util.List<SecureCustomerImportService.ImportedCustomer> importedCustomers(){return service.importedCustomers();}
    @GetMapping("/historico") public java.util.List<SecureCustomerImportService.BatchSummary> history(){return service.history();}
    @GetMapping("/{id}/arquivo-original")
    public ResponseEntity<byte[]> original(@PathVariable UUID id,Authentication auth){return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=importacao-original.csv")
      .contentType(MediaType.APPLICATION_OCTET_STREAM).header("X-Content-Type-Options","nosniff")
      .body(service.original(id,auth.getName()));}
}
