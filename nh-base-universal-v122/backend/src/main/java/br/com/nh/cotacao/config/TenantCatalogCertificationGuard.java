package br.com.nh.cotacao.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.beans.factory.annotation.Value;

/** Tenant always uses dedicated Flyway migrations. Manual certification flags cannot override this. */
@Configuration
public class TenantCatalogCertificationGuard {
    @Value("${app.tenant.mode:false}") private boolean tenantMode;
    @Value("${spring.flyway.locations:classpath:db/migration}") private String locations;

    @Bean
    public FlywayConfigurationCustomizer validateTenantSchemaProfile(Environment environment) {
        return configuration -> {
            boolean profile = environment.acceptsProfiles(Profiles.of("tenant"));
            if (tenantMode && !profile)
                throw new IllegalStateException("Tenant deve iniciar com SPRING_PROFILES_ACTIVE=tenant. Nunca aplique SQL legado a uma empresa nova.");
            if (profile && !tenantMode)
                throw new IllegalStateException("Perfil tenant não pode executar sem GS_TENANT_MODE=true.");
            if (tenantMode && !"classpath:db/tenant".equals(locations))
                throw new IllegalStateException("Migrações de tenant precisam vir exclusivamente de classpath:db/tenant.");
        };
    }
}
