package br.com.nh.cotacao.security;

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

    public AccessTokenService(
            @Value("${app.auth.token-secret}") String secret,
            @Value("${app.auth.token-hours:12}") long tokenHours
    ) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("AUTH_TOKEN_SECRET precisa ter pelo menos 32 caracteres.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tokenSeconds = Math.max(1, tokenHours) * 3600L;
    }

    public TokenResult issue(String username, PortalRole role) {
        long expiresAt = Instant.now().plusSeconds(tokenSeconds).getEpochSecond();
        String payload = encode(username) + "." + role.name() + "." + expiresAt + "." + UUID.randomUUID();
        String signature = sign(payload);
        return new TokenResult(payload + "." + signature, expiresAt);
    }

    public FaceChallengeResult issueFaceChallenge(String username, PortalRole role) {
        long expiresAt = Instant.now().plusSeconds(300).getEpochSecond();
        String payload = "face." + encode(username) + "." + role.name() + "." + expiresAt + "." + UUID.randomUUID();
        return new FaceChallengeResult(payload + "." + sign(payload), expiresAt);
    }

    public Optional<PortalPrincipal> verifyFaceChallenge(String challenge) {
        try {
            String[] parts = challenge.split("\\.");
            if (parts.length != 6 || !"face".equals(parts[0])) return Optional.empty();
            String payload = String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4]);
            byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
            byte[] actual = parts[5].getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();
            long expiresAt = Long.parseLong(parts[3]);
            if (Instant.now().getEpochSecond() >= expiresAt) return Optional.empty();
            return Optional.of(new PortalPrincipal(decode(parts[1]), PortalRole.valueOf(parts[2])));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public Optional<PortalPrincipal> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 5) return Optional.empty();
            String payload = String.join(".", parts[0], parts[1], parts[2], parts[3]);
            byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
            byte[] actual = parts[4].getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) return Optional.empty();
            long expiresAt = Long.parseLong(parts[2]);
            if (Instant.now().getEpochSecond() >= expiresAt) return Optional.empty();
            return Optional.of(new PortalPrincipal(decode(parts[0]), PortalRole.valueOf(parts[1])));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível assinar o token de acesso.", exception);
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public record TokenResult(String token, long expiresAtEpochSecond) {
    }

    public record FaceChallengeResult(String challenge, long expiresAtEpochSecond) {
    }
}
