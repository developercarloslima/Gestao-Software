package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.PortalPasskeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/admin/nh-ponto")
@PreAuthorize("hasRole('ADMIN')")
public class AdminNhPontoController {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper;
    private final PortalPasskeyService passkeyService;
    private final String internalUrl;
    private final String internalToken;

    public AdminNhPontoController(
            ObjectMapper mapper,
            PortalPasskeyService passkeyService,
            @Value("${app.nh-ponto.internal-url:http://ponto-api:3333}") String internalUrl,
            @Value("${app.nh-ponto.internal-token:}") String internalToken
    ) {
        this.mapper = mapper;
        this.passkeyService = passkeyService;
        this.internalUrl = internalUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
    }

    private PortalPrincipal assertPrincipalAdmin(Authentication authentication) {
        // A classe já é protegida por @PreAuthorize("hasRole('ADMIN')").
        // Portanto qualquer conta ADMIN ativa do painel principal pode consultar
        // os colaboradores do Apontamento Digital e, quando possuir passkey, aprovar/rejeitar
        // marcações pendentes. Isso mantém o painel local e o de produção coerentes.
        PortalPrincipal principal = (PortalPrincipal) authentication.getPrincipal();
        if (internalToken == null || internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Integração interna do Apontamento Digital não configurada.");
        }
        return principal;
    }

    private HttpRequest.Builder internalRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(internalUrl + path))
                .timeout(Duration.ofSeconds(12))
                .header("X-NH-Internal-Token", internalToken);
    }

    private JsonNode callInternalJson(String path, String method, JsonNode body) {
        try {
            HttpRequest.Builder builder = internalRequest(path).header("Accept", "application/json");
            if ("POST".equalsIgnoreCase(method)) {
                String payload = body == null ? "{}" : mapper.writeValueAsString(body);
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = "O Apontamento Digital não respondeu corretamente.";
                try {
                    JsonNode error = mapper.readTree(response.body());
                    if (error.hasNonNull("error")) detail = error.get("error").asText(detail);
                } catch (Exception ignored) {}
                HttpStatus mapped = switch (response.statusCode()) {
                    case 400 -> HttpStatus.BAD_REQUEST;
                    case 401 -> HttpStatus.UNAUTHORIZED;
                    case 403 -> HttpStatus.FORBIDDEN;
                    case 404 -> HttpStatus.NOT_FOUND;
                    case 409 -> HttpStatus.CONFLICT;
                    case 410 -> HttpStatus.GONE;
                    case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
                    default -> response.statusCode() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.BAD_REQUEST;
                };
                throw new ResponseStatusException(mapped, detail);
            }
            return mapper.readTree(response.body());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível consultar o Apontamento Digital agora.");
        }
    }

    private byte[] callInternalBytes(String path) {
        try {
            HttpResponse<byte[]> response = http.send(internalRequest(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(response.statusCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY, "Selfie da marcação não encontrada.");
            }
            return response.body();
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível carregar a selfie do Apontamento Digital agora.");
        }
    }

    @GetMapping("/overview")
    public JsonNode overview(Authentication authentication) {
        assertPrincipalAdmin(authentication);
        return callInternalJson("/internal/admin/overview", "GET", null);
    }

    @GetMapping("/journeys/{journeyId}")
    public JsonNode journeyDetails(@PathVariable String journeyId, Authentication authentication) {
        assertPrincipalAdmin(authentication);
        return callInternalJson("/internal/admin/journeys/" + journeyId, "GET", null);
    }

    @GetMapping("/employees/{employeeId}/punch-selfies")
    public JsonNode employeePunchSelfies(@PathVariable String employeeId, Authentication authentication) {
        assertPrincipalAdmin(authentication);
        return callInternalJson("/internal/admin/employees/" + employeeId + "/punch-selfies", "GET", null);
    }

    @GetMapping("/punches/{punchId}/selfie")
    public ResponseEntity<byte[]> punchSelfie(@PathVariable String punchId, Authentication authentication) {
        PortalPrincipal principal = assertPrincipalAdmin(authentication);
        String actorEmail = URLEncoder.encode(principal.username(), StandardCharsets.UTF_8);
        byte[] bytes = callInternalBytes("/internal/admin/punches/" + punchId + "/selfie?actorEmail=" + actorEmail);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .body(bytes);
    }

    @PostMapping("/punches/{punchId}/approval/options")
    public PortalPasskeyService.AssertionOptions approvalOptions(
            @PathVariable String punchId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        assertPrincipalAdmin(authentication);
        throw new ResponseStatusException(HttpStatus.GONE, "A aprovação individual foi desativada. Abra o NH Ponto e use Aprovação de jornadas.");
    }

    @PostMapping("/punches/{punchId}/review")
    public JsonNode reviewPunch(
            @PathVariable String punchId,
            @RequestBody ReviewPunchRequest request,
            Authentication authentication
    ) {
        assertPrincipalAdmin(authentication);
        throw new ResponseStatusException(HttpStatus.GONE, "A aprovação individual foi desativada. Abra o NH Ponto e use Aprovação de jornadas para conferir todas as evidências do dia.");
    }

    public record ReviewPunchRequest(
            @NotBlank String decision,
            @NotBlank String note,
            @NotBlank String rawId,
            @NotBlank String type,
            @NotBlank String clientDataJSON,
            @NotBlank String authenticatorData,
            @NotBlank String signature
    ) {}
}
