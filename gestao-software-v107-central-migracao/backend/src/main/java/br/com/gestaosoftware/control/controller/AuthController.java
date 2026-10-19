package br.com.gestaosoftware.control.controller;

import br.com.gestaosoftware.control.security.AccessTokenService;
import br.com.gestaosoftware.control.security.PlatformPrincipal;
import br.com.gestaosoftware.control.service.PlatformUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccessTokenService tokens;
    private final PlatformUserService users;
    public AuthController(AccessTokenService tokens, PlatformUserService users) { this.tokens=tokens; this.users=users; }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            var user = users.authenticate(request.username(), request.password());
            var issued = tokens.issue(user.username());
            return new LoginResponse(issued.token(), Instant.ofEpochSecond(issued.expiresAtEpochSecond()), user.username(), user.displayName(), "PLATFORM_ADMIN", user.passwordChangeRequired());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário ou senha inválidos.");
        }
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        PlatformPrincipal p=(PlatformPrincipal) authentication.getPrincipal();
        var user=users.session(p.username());
        return new MeResponse(user.username(),user.displayName(),"PLATFORM_ADMIN",user.passwordChangeRequired());
    }

    @PostMapping("/change-password")
    public MeResponse changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        PlatformPrincipal p=(PlatformPrincipal) authentication.getPrincipal();
        try {
            var user=users.changePassword(p.username(),request.currentPassword(),request.newPassword());
            return new MeResponse(user.username(),user.displayName(),"PLATFORM_ADMIN",user.passwordChangeRequired());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    public record LoginRequest(@NotBlank String username,@NotBlank String password){}
    public record ChangePasswordRequest(@NotBlank String currentPassword,@NotBlank String newPassword){}
    public record LoginResponse(String token, Instant expiresAt, String username, String displayName, String role, boolean passwordChangeRequired){}
    public record MeResponse(String username,String displayName,String role,boolean passwordChangeRequired){}
}
