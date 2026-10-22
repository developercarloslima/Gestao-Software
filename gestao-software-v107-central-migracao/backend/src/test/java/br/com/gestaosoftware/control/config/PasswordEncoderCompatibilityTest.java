package br.com.gestaosoftware.control.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncoderCompatibilityTest {
    private final PasswordEncoder encoder = new AppConfig().passwordEncoder();

    @Test void supportsLongPasswordsWithoutBcryptTruncation() {
        String prefix = "p".repeat(90);
        String password = prefix + "-secret-A";
        String hash = encoder.encode(password);
        assertTrue(hash.startsWith("{pbkdf2@SpringSecurity_v5_8}"));
        assertTrue(encoder.matches(password, hash));
        assertFalse(encoder.matches(prefix + "-secret-B", hash));
    }

    @Test void supportsExistingUnprefixedBcryptHashes() {
        String oldHash = new BCryptPasswordEncoder(4).encode("senha-antiga-123");
        assertTrue(encoder.matches("senha-antiga-123", oldHash));
        assertFalse(encoder.matches("senha-incorreta", oldHash));
    }

    @Test void supportsExplicitlyPrefixedBcryptHashes() {
        String oldHash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("senha-legada-123");
        assertTrue(encoder.matches("senha-legada-123", oldHash));
    }
}
