package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.QuoteDtos.OptionsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class PlanComparisonTokenService {
    private static final String HMAC = "HmacSHA256";
    private final byte[] secret;
    private final ObjectMapper objectMapper;
    private final long validitySeconds;

    public PlanComparisonTokenService(
            ObjectMapper objectMapper,
            @Value("${app.auth.token-secret}") String secret,
            @Value("${app.plan-comparison.valid-hours:120}") long validityHours
    ) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("AUTH_TOKEN_SECRET precisa ter pelo menos 32 caracteres.");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.validitySeconds = Math.max(1, validityHours) * 3600L;
    }

    public String issue(Payload source) {
        try {
            Payload payload = new Payload(
                    source.consultantId(), source.consultantName(), source.consultantWhatsapp(), source.customerName(), source.model(), source.plate(),
                    source.discountPercent(), source.auctionOrChassisRemarked(), source.indemnityFipePercent(), source.options(), Instant.now().plusSeconds(validitySeconds).getEpochSecond()
            );
            byte[] json = objectMapper.writeValueAsBytes(payload);
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(gzip(json));
            return body + "." + sign(body);
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível gerar o link de comparação.", exception);
        }
    }

    public Optional<Payload> verify(String token) {
        try {
            if (token == null || token.isBlank()) return Optional.empty();
            String[] parts = token.split("\\.");
            if (parts.length != 2) return Optional.empty();
            byte[] expected = sign(parts[0]).getBytes(StandardCharsets.UTF_8);
            byte[] actual = parts[1].getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();
            byte[] compressed = Base64.getUrlDecoder().decode(parts[0]);
            Payload payload = objectMapper.readValue(gunzip(compressed), Payload.class);
            if (payload.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) return Optional.empty();
            return Optional.of(payload);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private byte[] gzip(byte[] source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(source);
        }
        return output.toByteArray();
    }

    private byte[] gunzip(byte[] source) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(source));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            return output.toByteArray();
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível assinar o link de comparação.", exception);
        }
    }

    public record Payload(
            UUID consultantId,
            String consultantName,
            String consultantWhatsapp,
            String customerName,
            String model,
            String plate,
            Integer discountPercent,
            Boolean auctionOrChassisRemarked,
            Integer indemnityFipePercent,
            OptionsResponse options,
            long expiresAtEpochSecond
    ) {}
}
