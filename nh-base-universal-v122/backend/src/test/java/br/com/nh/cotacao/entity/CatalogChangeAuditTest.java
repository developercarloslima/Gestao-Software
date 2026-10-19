package br.com.nh.cotacao.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogChangeAuditTest {

    @Test
    void acceptsContractChangeRequestAuditType() {
        CatalogChangeAudit audit = CatalogChangeAudit.createText(
                "INSPECTION_CONTRACT_CHANGE_REQUEST",
                null,
                "inspection-id",
                "Revisão comercial aguardando confirmação do associado",
                "antes",
                "depois",
                "admin"
        );

        assertEquals("INSPECTION_CONTRACT_CHANGE_REQUEST", audit.getItemType());
    }

    @Test
    void rejectsInternalAuditTypeLongerThanDatabaseLimit() {
        String oversized = "X".repeat(CatalogChangeAudit.ITEM_TYPE_MAX_LENGTH + 1);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CatalogChangeAudit.createText(
                        oversized, null, "inspection-id", "Teste", null, null, "admin"
                )
        );

        assertEquals(
                "Identificador interno de auditoria excede o limite de 80 caracteres: " + oversized,
                error.getMessage()
        );
    }
}
