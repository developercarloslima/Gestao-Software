package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PlatformInvoiceDeletionTest {
    @Test void canCancelUnpaidLocalInvoices() {
        assertDoesNotThrow(() -> PlatformInvoiceCancellationService.assertLocallyCancelable("PENDING",false,false));
        assertDoesNotThrow(() -> PlatformInvoiceCancellationService.assertLocallyCancelable("OVERDUE",false,false));
    }
    @Test void neverCancelsPaidOrConfirmedLocalInvoices() {
        for(String status:new String[]{"PAID","PAID_IN_CASH","CONFIRMED","REFUNDED","CANCELLED","CREATING"}){
            assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
                () -> PlatformInvoiceCancellationService.assertLocallyCancelable(status,false,false)).getStatusCode());
        }
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> PlatformInvoiceCancellationService.assertLocallyCancelable("PENDING",true,false)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> PlatformInvoiceCancellationService.assertLocallyCancelable("PENDING",false,true)).getStatusCode());
    }
    @Test void recoveringStaleCreatingRequiresFiveMinutes() {
        java.time.Instant now=java.time.Instant.parse("2026-11-12T01:50:00Z");
        assertFalse(PlatformInvoiceCancellationService.staleCheckout(now.minusSeconds(60),now));
        assertFalse(PlatformInvoiceCancellationService.staleCheckout(now.minusSeconds(299),now));
        assertTrue(PlatformInvoiceCancellationService.staleCheckout(now.minusSeconds(300),now));
        assertTrue(PlatformInvoiceCancellationService.staleCheckout(now.minusSeconds(7200),now));
        assertFalse(PlatformInvoiceCancellationService.staleCheckout(null,now));
    }
    @Test void asaasOnlyAllowsOpenUnpaidStates() {
        for(String status:new String[]{"PENDING","OVERDUE","AWAITING_RISK_ANALYSIS"})
            assertDoesNotThrow(()->AsaasCheckoutService.assertCancelableAsaasStatus(status));
        for(String status:new String[]{"RECEIVED","CONFIRMED","RECEIVED_IN_CASH","REFUNDED","PARTIALLY_REFUNDED","DELETED","","UNKNOWN"})
            assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
                () -> AsaasCheckoutService.assertCancelableAsaasStatus(status)).getStatusCode());
    }
}
