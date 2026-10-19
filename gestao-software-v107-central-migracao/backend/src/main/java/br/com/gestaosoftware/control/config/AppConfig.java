package br.com.gestaosoftware.control.config;

import br.com.gestaosoftware.control.service.PlatformUserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AppConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean CommandLineRunner seedOwners(PlatformUserService users) { return args -> users.ensureOwners(); }
}
