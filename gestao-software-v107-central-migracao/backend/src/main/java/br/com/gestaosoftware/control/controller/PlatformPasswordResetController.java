package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.service.PlatformPasswordResetService;
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
    public PlatformPasswordResetController(PlatformPasswordResetService service){this.service=service;}
    public record Request(@NotBlank String username){}
    public record Confirm(@NotBlank String token,@NotBlank @Size(min=8,max=120) String newPassword){}
    public record Message(String message){}
    @PostMapping("/request")
    public Message request(@Valid @RequestBody Request payload){
        // Resposta indistinguível para contas inexistentes, inativas ou sem e-mail.
        try{service.request(payload.username());}
        catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Solicitação de reset indisponível: {}",ex.getClass().getSimpleName());}
        return new Message("Se há uma conta ativa com e-mail vinculado, o link de recuperação será enviado.");
    }
    @PostMapping("/confirm")
    public Message confirm(@Valid @RequestBody Confirm payload){
        try{service.confirm(payload.token(),payload.newPassword());}
        catch(IllegalArgumentException ex){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,ex.getMessage());
        }
        return new Message("Senha alterada. Faça login com a nova senha.");
    }
}
