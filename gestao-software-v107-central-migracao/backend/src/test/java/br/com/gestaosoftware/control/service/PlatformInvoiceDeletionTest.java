package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class PlatformInvoiceDeletionTest {
    @Test void allowsOnlyUnpaidUnissuedInvoices() {
        assertDoesNotThrow(() -> PlatformManagementService.assertInvoiceDeletionAllowed("PENDING", false));
        assertDoesNotThrow(() -> PlatformManagementService.assertInvoiceDeletionAllowed("OVERDUE", false));
    }
    @Test void blocksAnyInvoiceLinkedToAsaas() {
        for (String status : new String[]{"PENDING", "OVERDUE", "PAID", "CONFIRMED"}) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> PlatformManagementService.assertInvoiceDeletionAllowed(status, true));
            assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        }
    }
    @Test void preventsDeletingPaidOrReconciledHistory() {
        for (String status : new String[]{"PAID", "PAID_IN_CASH", "CONFIRMED", "REFUNDED", "CANCELLED"}) {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> PlatformManagementService.assertInvoiceDeletionAllowed(status, false)).getStatusCode());
        }
    }
}
