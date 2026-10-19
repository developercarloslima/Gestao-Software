package br.com.nh.cotacao.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/** Bloqueia configurações perigosas antes de liberar o processo HTTP do tenant. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantProvisioningGuard implements ApplicationRunner {
    @Value("${app.tenant.mode:false}") private boolean tenantMode;
    @Value("${app.tenant.display-name:}") private String name;
    @Value("${app.tenant.company-id:}") private String companyId;
    @Value("${app.gestao-control.tenant-host:}") private String host;
    @Value("${app.auth.admin-username:}") private String admin;
    @Value("${app.auth.admin-password:}") private String password;
    @Value("${app.demo-associate.enabled:false}") private boolean demo;
    @Value("${app.public-web-url:}") private String publicWeb;
    @Value("${app.auth.token-secret:}") private String jwtSecret;

    @Override public void run(ApplicationArguments args) {verifyConfiguration();}

    public void verifyConfiguration() {
        if (!tenantMode) return; // compatibilidade com a instalação NH já existente
        if (demo) fail("dados de demonstração não podem ser habilitados no tenant");
        if (blank(name) || blank(companyId) || blank(host) || blank(admin) || blank(publicWeb))
            fail("empresa, ID, domínio, URL e administrador precisam ser configurados");
        if (password == null || password.length() < 16) fail("senha inicial precisa ter 16+ caracteres");
        if (jwtSecret == null || jwtSecret.length() < 32 || jwtSecret.contains("troque-esta-chave"))
            fail("chave JWT precisa ser exclusiva da empresa e ter no mínimo 32 caracteres");
        try { java.util.UUID.fromString(companyId); } catch (IllegalArgumentException e) { fail("ID da empresa precisa ser UUID válido"); }
        String normalizedHost=host.trim().toLowerCase(Locale.ROOT);
        if (!normalizedHost.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}"))
            fail("domínio inválido");
        try {
            URI uri=URI.create(publicWeb);
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    !normalizedHost.equalsIgnoreCase(uri.getHost())) fail("URL pública HTTPS deve coincidir com o domínio da empresa");
        } catch (IllegalArgumentException ex) { fail("URL pública inválida"); }
        if (normalizedHost.equals("nh.gestaosoftware.com.br") || normalizedHost.contains("localhost"))
            fail("domínio de demonstração/legado não é permitido");
        if (admin.equalsIgnoreCase("admin@nh.local")) fail("login legado não é permitido");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void fail(String msg) { throw new IllegalStateException("Implantação tenant bloqueada: " + msg); }
}
