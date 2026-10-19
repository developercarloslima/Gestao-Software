package br.com.gestaosoftware.control.config;

import br.com.gestaosoftware.control.security.BearerTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenFilter filter) throws Exception {
        return http.csrf(csrf->csrf.disable()).cors(cors->{})
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth
                        .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                        .requestMatchers("/api/health","/api/auth/login","/api/public/**","/api/agent/**").permitAll()
                        .requestMatchers("/api/auth/**","/api/platform/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }
}
