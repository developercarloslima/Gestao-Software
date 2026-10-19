package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.entity.InspectionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class WhatsAppNotificationService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotificationService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String apiVersion;
    private final String phoneNumberId;
    private final String accessToken;
    private final String completionTemplateName;
    private final String languageCode;

    public WhatsAppNotificationService(
            ObjectMapper objectMapper,
            @Value("${app.whatsapp-cloud.enabled:false}") boolean enabled,
            @Value("${app.whatsapp-cloud.api-version:v23.0}") String apiVersion,
            @Value("${app.whatsapp-cloud.phone-number-id:}") String phoneNumberId,
            @Value("${app.whatsapp-cloud.access-token:}") String accessToken,
            @Value("${app.whatsapp-cloud.completion-template-name:}") String completionTemplateName,
            @Value("${app.whatsapp-cloud.language-code:pt_BR}") String languageCode
    ) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
        this.enabled = enabled;
        this.apiVersion = apiVersion == null || apiVersion.isBlank() ? "v23.0" : apiVersion.trim();
        this.phoneNumberId = safe(phoneNumberId);
        this.accessToken = safe(accessToken);
        this.completionTemplateName = safe(completionTemplateName);
        this.languageCode = languageCode == null || languageCode.isBlank() ? "pt_BR" : languageCode.trim();
    }

    public DeliveryResult sendInspectionCompleted(InspectionRequest inspection) {
        String message = "Olá, " + firstName(inspection.getAssociateName())
                + "! Sua vistoria foi realizada com sucesso. Aguarde a análise da equipe " + TenantIdentity.legalName() + ".";
        return send(inspection.getWhatsapp(), message, inspection.getAssociateName());
    }

    private DeliveryResult send(String rawPhone, String text, String associateName) {
        if (!enabled) return DeliveryResult.skip("Integração automática com WhatsApp desativada.");
        if (phoneNumberId.isBlank() || accessToken.isBlank()) {
            return DeliveryResult.failure("Configure WHATSAPP_CLOUD_PHONE_NUMBER_ID e WHATSAPP_CLOUD_ACCESS_TOKEN.");
        }
        String phone = normalizePhone(rawPhone);
        if (phone == null) return DeliveryResult.failure("WhatsApp do associado não informado ou inválido.");

        try {
            Map<String, Object> payload;
            if (!completionTemplateName.isBlank()) {
                payload = Map.of(
                        "messaging_product", "whatsapp",
                        "to", phone,
                        "type", "template",
                        "template", Map.of(
                                "name", completionTemplateName,
                                "language", Map.of("code", languageCode),
                                "components", List.of(Map.of(
                                        "type", "body",
                                        "parameters", List.of(Map.of("type", "text", "text", firstName(associateName)))
                                ))
                        )
                );
            } else {
                payload = Map.of(
                        "messaging_product", "whatsapp",
                        "recipient_type", "individual",
                        "to", phone,
                        "type", "text",
                        "text", Map.of("preview_url", false, "body", text)
                );
            }

            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages"))
                    .timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Aviso de conclusão da vistoria enviado ao WhatsApp final {}", lastDigits(phone));
                return DeliveryResult.success();
            }
            String error = "WhatsApp Cloud API retornou HTTP " + response.statusCode() + ": " + abbreviate(response.body());
            log.warn(error);
            return DeliveryResult.failure(error);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure("Envio ao WhatsApp interrompido.");
        } catch (Exception exception) {
            log.warn("Não foi possível enviar o aviso automático ao WhatsApp: {}", exception.getMessage());
            return DeliveryResult.failure("Não foi possível enviar o aviso automático ao WhatsApp.");
        }
    }

    private String normalizePhone(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 10 || digits.length() == 11) digits = "55" + digits;
        return digits.matches("^[1-9][0-9]{11,14}$") ? digits : null;
    }

    private String firstName(String name) {
        if (name == null || name.isBlank()) return "associado";
        return name.trim().split("\\s+")[0];
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }
    private String abbreviate(String value) {
        if (value == null) return "sem corpo";
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 350 ? clean : clean.substring(0, 350) + "…";
    }
    private String lastDigits(String phone) { return phone.length() <= 4 ? phone : "****" + phone.substring(phone.length() - 4); }

    public record DeliveryResult(boolean sent, boolean skipped, String detail) {
        public static DeliveryResult success() { return new DeliveryResult(true, false, null); }
        public static DeliveryResult skip(String detail) { return new DeliveryResult(false, true, detail); }
        public static DeliveryResult failure(String detail) { return new DeliveryResult(false, false, detail); }
    }
}
