package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class AsaasInvoiceReconciliationTest {
    private final LocalDate today = LocalDate.of(2026, 11, 3);
    private final LocalDate future = LocalDate.of(2026, 12, 10);
    private final LocalDate past = LocalDate.of(2026, 10, 10);

    private String state(String current, String provider, LocalDate due) {
        return ProvisioningService.reconciledInvoiceStatus(current, provider, due, today);
    }

    @Test void manualCashReceiptHasProviderStatusReceivedButBillingTypeReceivedInCash() {
        assertEquals("PAID_IN_CASH", ProvisioningService.reconciledInvoiceStatus(
                "PENDING", "RECEIVED", "RECEIVED_IN_CASH", future, today));
        assertEquals("PAID", ProvisioningService.reconciledInvoiceStatus(
                "PENDING", "RECEIVED", "PIX", future, today));
    }

    @Test void paidAtUsesAsaasPaymentDateInsteadOfReconciliationDate() throws Exception {
        var provider = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"paymentDate\":\"2026-11-03\"}");
        assertEquals(today, ProvisioningService.asaasPaymentDate(provider));
        assertNull(ProvisioningService.asaasPaymentDate(
                new com.fasterxml.jackson.databind.ObjectMapper().readTree("{}")));
    }

    @Test void pendingFutureBoletoDoesNotBecomeOverdue() {
        assertEquals("PENDING", state("PENDING", "PENDING", future));
    }

    @Test void manuallyReceivedCashDoesNotBecomeAvailableBalance() {
        assertEquals("PAID_IN_CASH", state("PENDING", "RECEIVED_IN_CASH", future));
        assertEquals("PAID_IN_CASH", state("PAID_IN_CASH", "CONFIRMED", future));
    }

    @Test void bankConfirmedDoesNotMeanBankReceived() {
        assertEquals("CONFIRMED", state("PENDING", "CONFIRMED", future));
        assertEquals("PAID", state("CONFIRMED", "RECEIVED", future));
    }

    @Test void overdueRevertsAfterPaidAndDoesNotRegress() {
        assertEquals("OVERDUE", state("PENDING", "PENDING", past));
        assertEquals("PAID", state("OVERDUE", "RECEIVED", past));
        assertEquals("PAID", state("PAID", "PENDING", past));
    }

    @Test void reversalOfManualCashClearsThePaidState() {
        assertEquals("PENDING", state("PAID_IN_CASH", "PENDING", future));
        assertEquals("OVERDUE", state("PAID_IN_CASH", "OVERDUE", past));
        assertEquals("CANCELLED", state("PAID_IN_CASH", "DELETED", past));
    }

    @Test void refundedPaymentOverridesPreviouslyReceived() {
        assertEquals("REFUNDED", state("PAID", "REFUNDED", past));
        assertEquals("REFUNDED", state("REFUNDED", "PENDING", past));
        assertNull(state("PENDING", "UNKNOWN_NEW_STATUS", future));
    }
}
