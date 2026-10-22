package br.com.gestaosoftware.control.config;

import br.com.gestaosoftware.control.service.PlatformUserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {
    /**
     * PBKDF2 nao trunca senhas com mais de 72 bytes, diferentemente do BCrypt.
     * Preserva a verificacao de hashes BCrypt legados sem regravar senhas antigas.
     */
    @Bean PasswordEncoder passwordEncoder() {
        var bcrypt = new BCryptPasswordEncoder(12);
        var pbkdf2 = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        var encoder = new DelegatingPasswordEncoder("pbkdf2@SpringSecurity_v5_8", Map.of(
                "pbkdf2@SpringSecurity_v5_8", pbkdf2,
                "bcrypt", bcrypt));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);
        return encoder;
    }
    @Bean CommandLineRunner seedOwners(PlatformUserService users) { return args -> users.ensureOwners(); }
}
