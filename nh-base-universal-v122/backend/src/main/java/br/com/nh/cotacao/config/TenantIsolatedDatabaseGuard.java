package br.com.nh.cotacao.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Rejects reused databases before Flyway migration, including existing NH history. */
@Configuration
public class TenantIsolatedDatabaseGuard {
    @Value("${app.tenant.mode:false}") private boolean tenantMode;
    private final TenantProvisioningGuard configuration;
    public TenantIsolatedDatabaseGuard(TenantProvisioningGuard configuration) {this.configuration=configuration;}

    @Bean
    public FlywayConfigurationCustomizer preventTenantDatabaseReuse(DataSource db) {
        return flyway -> {
            if (!tenantMode) return;
            configuration.verifyConfiguration(); // must refuse bad credentials before touching the database
            try (Connection c = db.getConnection()) {
                try (PreparedStatement q = c.prepareStatement("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")) {
                    try (ResultSet rs=q.executeQuery()) {
                        rs.next();
                        if (rs.getBoolean(1)) {
                            try (PreparedStatement h=c.prepareStatement("SELECT script, success FROM public.flyway_schema_history")) {
                                try (ResultSet versions=h.executeQuery()) {
                                    while (versions.next()) {
                                        String script=versions.getString(1);
                                        if (!versions.getBoolean(2) || script == null || !script.matches("V[0-9]+__[a-zA-Z0-9_]+\\.sql") ||
                                            !new ClassPathResource("db/tenant/"+script).exists())
                                            throw new IllegalStateException("Histórico do banco não pertence ao template universal; é proibido importar banco de outro cliente.");
                                    }
                                }
                            }
                            return;
                        }
                    }
                }
                try (PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")) {
                    try (ResultSet rs=q.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1)!=0L) throw new IllegalStateException("O banco já contém tabelas. Para tenant novo, use banco isolado e vazio.");
                    }
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException("Não foi possível validar o isolamento do banco do novo cliente.",exception);
            }
        };
    }
}
