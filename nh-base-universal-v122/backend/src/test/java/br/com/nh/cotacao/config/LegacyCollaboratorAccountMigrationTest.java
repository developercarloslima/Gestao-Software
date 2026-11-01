package br.com.nh.cotacao.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCollaboratorAccountMigrationTest {
    @Test
    void legacyBackfillIsLimitedToAutomaticallyCreatedPortalAccountsAndPreservesHistory() throws Exception {
        String resource = "/db/tenant/V15__deactivate_legacy_portal_collaborator_accounts.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "A migração deve acompanhar o deploy de todas as empresas existentes.");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("UPDATE portal_users AS account"));
            assertTrue(sql.contains("SET active = FALSE"));
            assertTrue(sql.contains("account.created_by = 'CONSULTANT_AUTO_BOOTSTRAP'"));
            assertTrue(sql.contains("collaborator.source = 'CREATED_IN_PORTAL'"));
            assertTrue(sql.contains("account.consultant_id = collaborator.id"));
            assertTrue(sql.contains("account.created_at <= collaborator.created_at + INTERVAL '2 minutes'"));
            assertFalse(sql.toUpperCase().contains("DELETE FROM"));
            assertFalse(sql.toUpperCase().contains("TRUNCATE "));
            assertFalse(sql.toUpperCase().contains("DROP TABLE"));
            assertFalse(sql.contains("UPDATE consultants"));
        }
    }
}
