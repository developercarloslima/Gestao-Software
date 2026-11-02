package br.com.nh.cotacao.service;

import br.com.nh.cotacao.security.AccessTokenService;
import br.com.nh.cotacao.security.PortalRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AccessTokenSecretTest {
    @Test
    void rejectsPublicOrMissingSigningKeys() {
        assertThrows(IllegalStateException.class, () -> new AccessTokenService("", 12));
        assertThrows(IllegalStateException.class, () -> new AccessTokenService("troque-esta-chave-local-com-pelo-menos-32-caracteres", 12));
    }

    @Test
    void capsSessionsAtTwentyFourHours() {
        AccessTokenService tokens = new AccessTokenService("unique-test-signing-key-not-used-in-real-services-2026", 720);
        var issued = tokens.issue("colaborador", PortalRole.CONSULTANT);
        assertTrue(issued.expiresAtEpochSecond() <= Instant.now().plusSeconds(24*3600L+2).getEpochSecond());
        assertTrue(tokens.verify(issued.token()).isPresent());
    }
}
