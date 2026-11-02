package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.service.TenantAdminPasswordResetService;
import br.com.nh.cotacao.security.AuthAttemptLimiter;
import jakarta.servlet.http.HttpServletRequest;
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
    private final AuthAttemptLimiter limiter;
    public TenantAdminPasswordResetController(TenantAdminPasswordResetService service,AuthAttemptLimiter limiter){
        this.service=service;this.limiter=limiter;
    }
    public record Request(@NotBlank String username){}
    public record Confirm(@NotBlank String token,@NotBlank @Size(min=8,max=120) String newPassword){}
    public record Message(String message){}
    @PostMapping("/request")
    public Message request(@Valid @RequestBody Request input,HttpServletRequest servletRequest){
        limiter.recovery(input.username(),servletRequest);
        try{service.request(input.username());}
        catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Recuperação do administrador indisponível: {}",ex.getClass().getSimpleName());}
        return new Message("Se existe um administrador com e-mail vinculado, o link será enviado.");
    }
    @PostMapping("/confirm")
    public Message confirm(@Valid @RequestBody Confirm input,HttpServletRequest servletRequest){
        limiter.verifyRecovery(servletRequest);
        try{service.confirm(input.token(),input.newPassword());}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
        return new Message("Senha redefinida. Entre novamente com a nova senha.");
    }
}
