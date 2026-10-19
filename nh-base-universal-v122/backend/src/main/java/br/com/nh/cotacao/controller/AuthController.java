package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.AccessTokenService;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import br.com.nh.cotacao.service.PortalFaceService;
import br.com.nh.cotacao.service.PortalPasskeyService;
import br.com.nh.cotacao.service.PortalUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccessTokenService tokenService;
    private final PortalUserService portalUserService;
    private final PortalFaceService portalFaceService;
    private final PortalPasskeyService portalPasskeyService;

    public AuthController(
            AccessTokenService tokenService,
            PortalUserService portalUserService,
            PortalFaceService portalFaceService,
            PortalPasskeyService portalPasskeyService
    ) {
        this.tokenService = tokenService;
        this.portalUserService = portalUserService;
        this.portalFaceService = portalFaceService;
        this.portalPasskeyService = portalPasskeyService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        try {
            var authenticated = portalUserService.authenticate(request.username(), request.password());
            var face = portalFaceService.state(authenticated.username(), authenticated.role());
            if (face.required()) {
                var challenge = tokenService.issueFaceChallenge(authenticated.username(), authenticated.role());
                return new LoginResponse(
                        null, authenticated.role(), Instant.ofEpochSecond(challenge.expiresAtEpochSecond()),
                        authenticated.consultantId(), authenticated.consultantName(), authenticated.passwordChangeRequired(),
                        true, face.enrolled(), challenge.challenge(), false, false, null, null
                );
            }
            if (portalPasskeyService.required(authenticated.role())) {
                boolean enrolled = portalPasskeyService.enrolled(authenticated.username());
                if (enrolled) {
                    var assertion = portalPasskeyService.beginAssertion(authenticated.username(), servletRequest);
                    return new LoginResponse(
                            null, authenticated.role(), Instant.now().plusSeconds(180),
                            authenticated.consultantId(), authenticated.consultantName(), authenticated.passwordChangeRequired(),
                            false, false, null, true, true, null, assertion
                    );
                }
                // V83.21: após um reset de passkeys, usuário + senha liberam o painel.
                // O próprio painel avisa que a nova passkey obrigatória precisa ser cadastrada.
                return issueAccess(authenticated.username());
            }
            return issueAccess(authenticated.username());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário ou senha inválidos.");
        }
    }

    @PostMapping(value = "/face/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoginResponse registerFace(
            @RequestParam("challenge") String challenge,
            @RequestPart("image") MultipartFile image
    ) {
        PortalPrincipal principal = facePrincipal(challenge);
        try {
            portalFaceService.register(principal.username(), principal.role(), image);
            return issueAccess(principal.username());
        } catch (PortalFaceService.FaceEnrollmentMissingException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping(value = "/face/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoginResponse verifyFace(
            @RequestParam("challenge") String challenge,
            @RequestPart("image") MultipartFile image
    ) {
        PortalPrincipal principal = facePrincipal(challenge);
        try {
            portalFaceService.verify(principal.username(), principal.role(), image);
            return issueAccess(principal.username());
        } catch (PortalFaceService.FaceEnrollmentMissingException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage());
        }
    }

    @PostMapping("/passkey/register/finish")
    public LoginResponse finishPasskeyRegistration(@Valid @RequestBody PasskeyRegistrationFinishRequest request) {
        try {
            portalPasskeyService.finishRegistration(
                    request.username(),
                    new PortalPasskeyService.RegistrationFinish(
                            request.rawId(), request.type(), request.clientDataJSON(), request.attestationObject(),
                            request.authenticatorData(), request.publicKey(), request.publicKeyAlgorithm()
                    )
            );
            return issueAccess(request.username());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage());
        }
    }

    @PostMapping("/passkey/assertion/finish")
    public LoginResponse finishPasskeyAssertion(@Valid @RequestBody PasskeyAssertionFinishRequest request) {
        try {
            portalPasskeyService.finishAssertion(
                    request.username(),
                    new PortalPasskeyService.AssertionFinish(request.rawId(), request.type(), request.clientDataJSON(), request.authenticatorData(), request.signature())
            );
            return issueAccess(request.username());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, exception.getMessage());
        }
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        var session = portalUserService.session(principal.username());
        return new MeResponse(
                session.username(), session.displayName(), session.role(), session.consultantId(), session.consultantName(),
                session.passwordChangeRequired()
        );
    }

    @PostMapping("/change-password")
    public MeResponse changePassword(@Valid @RequestBody ChangeOwnPasswordRequest request, Authentication authentication) {
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        portalUserService.changeOwnPassword(principal.username(), request.currentPassword(), request.newPassword());
        var session = portalUserService.session(principal.username());
        return new MeResponse(
                session.username(), session.displayName(), session.role(), session.consultantId(), session.consultantName(),
                session.passwordChangeRequired()
        );
    }

    private PortalPrincipal facePrincipal(String challenge) {
        return tokenService.verifyFaceChallenge(challenge)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Validação facial expirada. Informe usuário e senha novamente."));
    }

    private LoginResponse issueAccess(String username) {
        var authenticated = portalUserService.completeLogin(username);
        var issued = tokenService.issue(authenticated.username(), authenticated.role());
        var face = portalFaceService.state(authenticated.username(), authenticated.role());
        return new LoginResponse(
                issued.token(), authenticated.role(), Instant.ofEpochSecond(issued.expiresAtEpochSecond()),
                authenticated.consultantId(), authenticated.consultantName(), authenticated.passwordChangeRequired(),
                false, face.enrolled(), null, false, portalPasskeyService.required(authenticated.role()) && portalPasskeyService.enrolled(authenticated.username()), null, null
        );
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record PasskeyRegistrationFinishRequest(
            @NotBlank String username, @NotBlank String rawId, @NotBlank String type,
            @NotBlank String clientDataJSON, @NotBlank String attestationObject,
            String authenticatorData, String publicKey, Integer publicKeyAlgorithm
    ) {}
    public record PasskeyAssertionFinishRequest(
            @NotBlank String username, @NotBlank String rawId, @NotBlank String type,
            @NotBlank String clientDataJSON, @NotBlank String authenticatorData, @NotBlank String signature
    ) {}
    public record ChangeOwnPasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record LoginResponse(
            String token, PortalRole role, Instant expiresAt, java.util.UUID consultantId, String consultantName,
            boolean passwordChangeRequired, boolean faceRequired, boolean faceEnrolled, String faceChallenge,
            boolean passkeyRequired, boolean passkeyEnrolled, PortalPasskeyService.RegistrationOptions passkeyRegistration,
            PortalPasskeyService.AssertionOptions passkeyAssertion
    ) {}
    public record MeResponse(
            String username, String displayName, PortalRole role, java.util.UUID consultantId, String consultantName,
            boolean passwordChangeRequired
    ) {}
}
