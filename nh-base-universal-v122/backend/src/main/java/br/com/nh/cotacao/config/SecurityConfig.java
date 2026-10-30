package br.com.nh.cotacao.config;

import jakarta.servlet.DispatcherType;
import br.com.nh.cotacao.security.BearerTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/health", "/api/health/tenant", "/api/auth/login", "/api/auth/password-reset/**", "/api/auth/face/**", "/api/auth/passkey/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/quotes/*/pdf").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/tow/**").authenticated()
                        .requestMatchers("/api/workshop/**").hasAnyRole("WORKSHOP_MANAGER", "SUPERVISION_ANALYSIS", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/procurement/**").hasRole("BUYER")
                        .requestMatchers("/api/procurement/**").hasAnyRole("BUYER", "ADMIN")
                        .requestMatchers("/api/checklist/**").hasAnyRole("EVENT_OPERATOR", "ANALYST", "SUPERVISION_ANALYSIS", "WORKSHOP_MANAGER", "ADMIN")
                        .requestMatchers("/api/analysis/**").hasAnyRole("ANALYST", "ADMIN")
                        .requestMatchers("/api/supervision/**").hasAnyRole("SUPERVISION_ANALYSIS", "ADMIN")
                        .requestMatchers("/api/consultant-dashboard/**").hasAnyRole("CONSULTANT", "ADMIN")
                        .requestMatchers("/api/associate-portal/**").hasAnyRole("ASSOCIATE", "ADMIN")
                        .requestMatchers("/api/finance-office/**").hasAnyRole("BUYER", "ADMIN")
                        // O perfil TOW_DRIVER é deliberadamente isolado: fora de /api/tow e /api/auth,
                        // os demais endpoints internos continuam restritos aos perfis da plataforma principal.
                        .requestMatchers("/api/**").hasAnyRole("CONSULTANT", "ANALYST", "SUPERVISION_ANALYSIS", "WORKSHOP_MANAGER", "ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
