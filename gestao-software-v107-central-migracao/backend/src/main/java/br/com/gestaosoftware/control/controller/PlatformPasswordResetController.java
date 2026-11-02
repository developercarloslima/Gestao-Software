package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.service.PlatformPasswordResetService;
import br.com.gestaosoftware.control.security.AuthAttemptLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth/password-reset")
public class PlatformPasswordResetController {
    private final PlatformPasswordResetService service;
    private final AuthAttemptLimiter limiter;
    public PlatformPasswordResetController(PlatformPasswordResetService service, AuthAttemptLimiter limiter){
        this.service=service;this.limiter=limiter;
    }
    public record Request(@NotBlank String username){}
    public record Confirm(@NotBlank String token,@NotBlank @Size(min=8,max=120) String newPassword){}
    public record Message(String message){}
    @PostMapping("/request")
    public Message request(@Valid @RequestBody Request payload, HttpServletRequest servletRequest){
        limiter.recovery(payload.username(), servletRequest);
        // Resposta indistinguível para contas inexistentes, inativas ou sem e-mail.
        try{service.request(payload.username());}
        catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Solicitação de reset indisponível: {}",ex.getClass().getSimpleName());}
        return new Message("Se há uma conta ativa com e-mail vinculado, o link de recuperação será enviado.");
    }
    @PostMapping("/confirm")
    public Message confirm(@Valid @RequestBody Confirm payload, HttpServletRequest servletRequest){
        limiter.verifyRecovery(servletRequest);
        try{service.confirm(payload.token(),payload.newPassword());}
        catch(IllegalArgumentException ex){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,ex.getMessage());
        }
        return new Message("Senha alterada. Faça login com a nova senha.");
    }
}
