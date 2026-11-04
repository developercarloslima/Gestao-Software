package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformFinancialEmailTest {
    @Test void optionalUntilAsaasEnrollmentButMustBeValidWhenSet() {
        assertNull(PlatformManagementService.validatedFinancialEmail(null));
        assertNull(PlatformManagementService.validatedFinancialEmail("  "));
        assertEquals("financeiro@cliente.com.br",
                PlatformManagementService.validatedFinancialEmail(" financeiro@cliente.com.br "));
        assertThrows(IllegalArgumentException.class,
                () -> PlatformManagementService.validatedFinancialEmail("sem-arroba"));
        assertThrows(IllegalArgumentException.class,
                () -> PlatformManagementService.validatedFinancialEmail("nome @cliente.com.br"));
        assertThrows(IllegalArgumentException.class,
                () -> PlatformManagementService.validatedFinancialEmail("x".repeat(181) + "@cliente.com.br"));
    }
}
