package br.com.nh.cotacao.security;

import br.com.nh.cotacao.service.PortalUserService;
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
    private final AccessTokenService tokenService;
    private final PortalUserService portalUserService;

    public BearerTokenFilter(AccessTokenService tokenService, PortalUserService portalUserService) {
        this.tokenService = tokenService;
        this.portalUserService = portalUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            tokenService.verify(authorization.substring(7).trim()).ifPresent(principal -> {
                // Uma conta desativada ou que teve o perfil alterado perde acesso imediatamente,
                // mesmo que ainda possua um token assinado dentro do prazo de validade.
                if (!portalUserService.isActiveWithRole(principal.username(), principal.role())) return;
                String uri = request.getRequestURI();
                boolean passwordEndpoint = "/api/auth/me".equals(uri) || "/api/auth/change-password".equals(uri);
                if (portalUserService.requiresPasswordChange(principal.username()) && !passwordEndpoint) return;
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }
}
