package br.com.gestaosoftware.control.service;

import br.com.gestaosoftware.control.security.AccessTokenService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AccessTokenSecretTest {
    @Test
    void rejectsPublicOrMissingSigningKeys() {
        assertThrows(IllegalStateException.class, () -> new AccessTokenService("", 12));
        assertThrows(IllegalStateException.class, () -> new AccessTokenService("gestao-software-local-secret-change-me-2026", 12));
        assertThrows(IllegalStateException.class, () -> new AccessTokenService("troque-a-chave-publica-com-mais-de-32-caracteres", 12));
    }

    @Test
    void capsSessionsAtTwentyFourHours() {
        AccessTokenService tokens = new AccessTokenService("unique-test-signing-key-not-used-in-real-services-2026", 720);
        var issued = tokens.issue("carlos.lima");
        assertTrue(issued.expiresAtEpochSecond() <= Instant.now().plusSeconds(24*3600L+2).getEpochSecond());
        assertTrue(tokens.verify(issued.token()).isPresent());
    }
}
