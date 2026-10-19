package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.PortalPasskeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/profile/passkeys")
public class AdminProfilePasskeyController {
    private static final Logger log = LoggerFactory.getLogger(AdminProfilePasskeyController.class);
    private final PortalPasskeyService passkeyService;

    public AdminProfilePasskeyController(PortalPasskeyService passkeyService) {
        this.passkeyService = passkeyService;
    }

    @GetMapping
    public List<PortalPasskeyService.PasskeyInfo> list(Authentication auth) {
        return passkeyService.listOwn(username(auth));
    }

    @PostMapping("/register/options")
    public PortalPasskeyService.RegistrationOptions options(Authentication auth, HttpServletRequest request) {
        try {
            return passkeyService.beginProfileRegistration(username(auth), request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    @PostMapping("/register/finish")
    public PortalPasskeyService.PasskeyInfo finish(@Valid @RequestBody RegisterRequest request, Authentication auth) {
        try {
            return passkeyService.finishProfileRegistration(
                    username(auth),
                    new PortalPasskeyService.RegistrationFinish(
                            request.rawId(), request.type(), request.clientDataJSON(), request.attestationObject(),
                            request.authenticatorData(), request.publicKey(), request.publicKeyAlgorithm()
                    ),
                    request.label()
            );
        } catch (IllegalArgumentException exception) {
            log.warn("Falha ao concluir cadastro de passkey do ADMIN: {}", exception.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, Authentication auth) {
        try {
            passkeyService.revokeOwn(username(auth), id);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    private String username(Authentication auth) {
        return ((PortalPrincipal) auth.getPrincipal()).username();
    }

    public record RegisterRequest(
            @NotBlank String rawId,
            @NotBlank String type,
            @NotBlank String clientDataJSON,
            @NotBlank String attestationObject,
            String authenticatorData,
            String publicKey,
            Integer publicKeyAlgorithm,
            @Size(max = 120) String label
    ) {}
}
