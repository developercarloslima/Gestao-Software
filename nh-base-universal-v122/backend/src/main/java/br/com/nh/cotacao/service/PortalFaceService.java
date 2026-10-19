package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@Service
public class PortalFaceService {
    private final PortalUserRepository repository;
    private final RestClient client;
    private final String apiKey;

    public PortalFaceService(
            PortalUserRepository repository,
            RestClient.Builder builder,
            @Value("${app.face-auth.internal-url:http://face-api:8000}") String internalUrl,
            @Value("${app.face-auth.api-key:}") String apiKey
    ) {
        this.repository = repository;
        this.client = builder.baseUrl(internalUrl.replaceAll("/+$", "")).build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean requiresFaceValidation(PortalUser user) {
        // v83.19: reconhecimento facial não é fator de login de nenhum portal.
        // ADMIN usa usuário + senha + passkey; demais perfis usam apenas usuário + senha.
        return false;
    }

    @Transactional(readOnly = true)
    public FaceState state(String username, PortalRole expectedRole) {
        PortalUser user = findActive(username);
        if (expectedRole != null && user.getRole() != expectedRole) {
            throw new IllegalArgumentException("O perfil desta conta foi alterado. Entre novamente.");
        }
        return new FaceState(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                requiresFaceValidation(user), user.getFaceEnrolledAt() != null
        );
    }

    @Transactional
    public void register(String username, PortalRole expectedRole, MultipartFile image) {
        PortalUser user = findActive(username);
        assertRoleAndRequirement(user, expectedRole);
        // O cadastro pode ser repetido para reconstruir/substituir a referência do ADMIN.
        // Isso também recupera automaticamente casos em que o banco principal indica
        // cadastro ativo, mas o volume da Face API foi recriado.
        validateImage(image);
        MultiValueMap<String, Object> body = multipart(user, image);
        try {
            client.post().uri("/api/v1/register")
                    .headers(headers -> { if (!apiKey.isBlank()) headers.set("X-API-Key", apiKey); })
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            throw new IllegalArgumentException(faceApiMessage(ex, "Não foi possível cadastrar o rosto. Tente novamente com boa iluminação."));
        } catch (RestClientException ex) {
            throw new IllegalStateException("Serviço de validação facial indisponível no momento. Tente novamente.");
        }
        user.markFaceEnrolled();
        repository.saveAndFlush(user);
    }

    @Transactional(noRollbackFor = FaceEnrollmentMissingException.class)
    public void verify(String username, PortalRole expectedRole, MultipartFile image) {
        PortalUser user = findActive(username);
        assertRoleAndRequirement(user, expectedRole);
        if (user.getFaceEnrolledAt() == null) {
            throw new FaceEnrollmentMissingException("Cadastro facial pendente para este usuário.");
        }
        validateImage(image);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("external_id", externalId(user));
        body.add("image", imageResource(image));
        try {
            Map<?, ?> result = client.post().uri("/api/v1/verify")
                    .headers(headers -> { if (!apiKey.isBlank()) headers.set("X-API-Key", apiKey); })
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (result == null || !Boolean.TRUE.equals(result.get("matched"))) {
                throw new IllegalArgumentException("Rosto não corresponde ao usuário informado. Tente novamente.");
            }
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            // Se o volume da Face API tiver sido recriado, permite reconstruir a referência
            // no próximo passo sem exigir alteração manual no banco principal.
            user.clearFaceEnrollment();
            repository.saveAndFlush(user);
            throw new FaceEnrollmentMissingException("A referência facial precisa ser cadastrada novamente.");
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw new IllegalArgumentException(faceApiMessage(ex, "Não foi possível validar o rosto. Tente novamente."));
        } catch (RestClientException ex) {
            throw new IllegalStateException("Serviço de validação facial indisponível no momento. Tente novamente.");
        }
    }

    @Transactional
    public void reset(UUID userId) {
        PortalUser user = repository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Conta de acesso não encontrada."));
        try {
            client.delete().uri("/api/v1/employees/{externalId}", externalId(user))
                    .headers(headers -> { if (!apiKey.isBlank()) headers.set("X-API-Key", apiKey); })
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is5xxServerError()) {
                throw new IllegalStateException("A API facial não respondeu ao reset. Tente novamente.");
            }
        } catch (RestClientException ex) {
            throw new IllegalStateException("Serviço de validação facial indisponível no momento. Tente novamente.");
        }
        user.clearFaceEnrollment();
        repository.saveAndFlush(user);
    }

    private PortalUser findActive(String username) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Conta de acesso não encontrada ou inativa."));
    }

    private void assertRoleAndRequirement(PortalUser user, PortalRole expectedRole) {
        if (user.getRole() != expectedRole) throw new IllegalArgumentException("O perfil desta conta foi alterado. Entre novamente.");
        if (!requiresFaceValidation(user)) throw new IllegalArgumentException("Esta conta não exige validação facial.");
    }

    private MultiValueMap<String, Object> multipart(PortalUser user, MultipartFile image) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("external_id", externalId(user));
        body.add("name", user.getDisplayName() == null || user.getDisplayName().isBlank() ? user.getUsername() : user.getDisplayName());
        body.add("image", imageResource(image));
        return body;
    }

    private Object imageResource(MultipartFile image) {
        try {
            String contentType = image.getContentType() == null ? MediaType.IMAGE_JPEG_VALUE : image.getContentType();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            return new org.springframework.http.HttpEntity<>(new ByteArrayResource(image.getBytes()) {
                @Override public String getFilename() { return "face.jpg"; }
            }, headers);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Não foi possível ler a imagem da câmera.");
        }
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) throw new IllegalArgumentException("Capture uma imagem do rosto para continuar.");
        if (image.getSize() > 6L * 1024 * 1024) throw new IllegalArgumentException("A imagem facial excedeu o limite permitido.");
        String type = image.getContentType() == null ? "" : image.getContentType().toLowerCase();
        if (!type.startsWith("image/")) throw new IllegalArgumentException("O arquivo enviado não é uma imagem válida.");
    }

    private String externalId(PortalUser user) { return "portal-" + user.getId(); }

    private String faceApiMessage(RestClientResponseException ex, String fallback) {
        String raw = ex.getResponseBodyAsString();
        if (raw == null || raw.isBlank()) return fallback;
        if (raw.contains("pouca qualidade")) return "A imagem está com pouca qualidade. Melhore a iluminação, centralize o rosto e tente novamente.";
        if (raw.contains("Nenhum rosto") || raw.contains("nenhum rosto")) return "Nenhum rosto foi identificado. Centralize o rosto na câmera e tente novamente.";
        if (raw.contains("mais de um rosto") || raw.contains("Mais de um rosto")) return "Há mais de um rosto na imagem. Deixe somente o titular da conta diante da câmera.";
        return fallback;
    }

    public record FaceState(UUID userId, String username, String displayName, PortalRole role, boolean required, boolean enrolled) {}
    public static class FaceEnrollmentMissingException extends IllegalArgumentException {
        public FaceEnrollmentMissingException(String message) { super(message); }
    }
}
