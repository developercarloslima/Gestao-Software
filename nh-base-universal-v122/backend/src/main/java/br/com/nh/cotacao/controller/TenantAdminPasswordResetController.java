package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.TenantAdminPasswordResetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth/password-reset")
@ConditionalOnProperty(name="app.tenant.mode",havingValue="true")
public class TenantAdminPasswordResetController {
    private final TenantAdminPasswordResetService service;
    public TenantAdminPasswordResetController(TenantAdminPasswordResetService service){this.service=service;}
    public record Request(@NotBlank String username){}
    public record Confirm(@NotBlank String token,@NotBlank @Size(min=8,max=120) String newPassword){}
    public record Message(String message){}
    @PostMapping("/request")
    public Message request(@Valid @RequestBody Request input){
        try{service.request(input.username());}
        catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Recuperação do administrador indisponível: {}",ex.getClass().getSimpleName());}
        return new Message("Se existe um administrador com e-mail vinculado, o link será enviado.");
    }
    @PostMapping("/confirm")
    public Message confirm(@Valid @RequestBody Confirm input){
        try{service.confirm(input.token(),input.newPassword());}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        return new Message("Senha redefinida. Entre novamente com a nova senha.");
    }
}
