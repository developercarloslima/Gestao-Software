package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class PlatformFinancialIntegrityTest {
    @Test
    void manualPaymentConfirmationIsForbidden() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.assertManualInvoiceChangeAllowed("PENDING", "PAID", false));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void settledInvoicesAreImmutable() {
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.assertManualInvoiceChangeAllowed("PAID", "PENDING", false)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.assertManualInvoiceChangeAllowed("PAID", null, false)).getStatusCode());
    }

    @Test
    void providerLinkedInvoicesCannotBeChangedManually() {
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.assertManualInvoiceChangeAllowed("PENDING", null, true)).getStatusCode());
    }

    @Test
    void pendingUnlinkedInvoiceCanBeAdjustedWithoutDeclaringPayment() {
        assertDoesNotThrow(() -> PlatformManagementService.assertManualInvoiceChangeAllowed("PENDING", null, false));
        assertDoesNotThrow(() -> PlatformManagementService.assertManualInvoiceChangeAllowed("OVERDUE", "PENDING", false));
    }
}
