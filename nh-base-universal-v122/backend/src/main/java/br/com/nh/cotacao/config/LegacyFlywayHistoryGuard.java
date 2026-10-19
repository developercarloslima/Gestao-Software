package br.com.nh.cotacao.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Garante que as excecoes de compatibilidade do Flyway sejam aplicadas somente
 * ao historico especifico encontrado no NH. Nunca reescreve checksums.
 */
@Configuration
@ConditionalOnProperty(name="app.tenant.mode", havingValue="false", matchIfMissing=true)
public class LegacyFlywayHistoryGuard {
    // Checksum/descritivo identificados no historico real antes da atualizacao.
    private static final int V87_LEGACY = 1996736181;
    private static final int V88_LEGACY = -624860850;
    // Algumas instalacoes de teste receberam as migracoes que foram renumeradas.
    private static final int V87_NEW = 557977593;
    private static final int V88_NEW = 1572973540;

    @Bean
    public FlywayConfigurationCustomizer validateNhHistoricalMigrationCompatibility(DataSource dataSource) {
        return configuration -> verifyHistory(dataSource);
    }

    private void verifyHistory(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            try (ResultSet table = c.getMetaData().getTables(null, "public", "flyway_schema_history", new String[]{"TABLE"})) {
                if (!table.next()) return; // banco novo
            }
            try (PreparedStatement query = c.prepareStatement(
                "SELECT version, description, checksum, script, success FROM flyway_schema_history WHERE version IS NOT NULL")) {
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        String version = rows.getString("version");
                        String description = rows.getString("description");
                        int checksum = rows.getInt("checksum");
                        boolean success = rows.getBoolean("success");
                        String script = rows.getString("script");
                        // Nao autoriza ocultar outros scripts que tenham sido removidos acidentalmente.
                        boolean missing = !new ClassPathResource("db/migration/" + script).exists();
                        if (!"87".equals(version) && !"88".equals(version)) {
                            if (missing) {
                                throw new IllegalStateException("Migracao ja aplicada sumiu do projeto: " + script);
                            }
                            continue; // checksums das demais versoes sao verificados pelo proprio Flyway.
                        }
                        boolean valid = missing && success && (
                            ("87".equals(version) && ((checksum == V87_LEGACY && "platform multi company management".equals(description))
                                || (checksum == V87_NEW && "persist gestao entitlements".equals(description))))
                            || ("88".equals(version) && ((checksum == V88_LEGACY && "gestao platform branding white label".equals(description))
                                || (checksum == V88_NEW && "asaas fee passthrough".equals(description))))
                        );
                        if (!valid) {
                            throw new IllegalStateException("Historico Flyway V" + version
                                + " diferente do ambiente auditado. Interrompendo para nao arriscar dados: "
                                + description + " (checksum=" + checksum + ").");
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Nao foi possivel validar o historico do Flyway com seguranca.", ex);
        }
    }
}
