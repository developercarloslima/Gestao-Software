package br.com.gestaosoftware.control.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccessTokenService {
    private static final String HMAC = "HmacSHA256";
    private final byte[] secret;
    private final long tokenSeconds;

    public AccessTokenService(@Value("${app.auth.token-secret}") String secret,
                              @Value("${app.auth.token-hours:12}") long tokenHours) {
        if (secret == null || secret.length() < 32) throw new IllegalStateException("AUTH_TOKEN_SECRET precisa ter pelo menos 32 caracteres.");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tokenSeconds = Math.max(1, tokenHours) * 3600L;
    }

    public TokenResult issue(String username) {
        long expiresAt = Instant.now().plusSeconds(tokenSeconds).getEpochSecond();
        String payload = encode(username) + "." + expiresAt + "." + UUID.randomUUID();
        return new TokenResult(payload + "." + sign(payload), expiresAt);
    }

    public Optional<PlatformPrincipal> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 4) return Optional.empty();
            String payload = String.join(".", parts[0], parts[1], parts[2]);
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), parts[3].getBytes(StandardCharsets.UTF_8))) return Optional.empty();
            long expiresAt = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() >= expiresAt) return Optional.empty();
            return Optional.of(new PlatformPrincipal(decode(parts[0])));
        } catch (Exception ignored) { return Optional.empty(); }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Não foi possível assinar o token.", exception); }
    }
    private String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    public record TokenResult(String token, long expiresAtEpochSecond) {}
}
