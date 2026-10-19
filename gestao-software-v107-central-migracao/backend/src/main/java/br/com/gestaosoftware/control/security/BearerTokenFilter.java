package br.com.gestaosoftware.control.security;

import br.com.gestaosoftware.control.service.PlatformUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final AccessTokenService tokens;
    private final PlatformUserService users;
    public BearerTokenFilter(AccessTokenService tokens, PlatformUserService users) { this.tokens=tokens; this.users=users; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            tokens.verify(auth.substring(7).trim()).ifPresent(principal -> {
                if (!users.active(principal.username())) return;
                boolean passwordEndpoint = "/api/auth/me".equals(request.getRequestURI()) || "/api/auth/change-password".equals(request.getRequestURI());
                if (users.requiresPasswordChange(principal.username()) && !passwordEndpoint) return;
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))));
            });
        }
        chain.doFilter(request,response);
    }
}
