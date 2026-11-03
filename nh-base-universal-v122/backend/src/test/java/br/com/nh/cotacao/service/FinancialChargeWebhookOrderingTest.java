package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialChargeWebhookOrderingTest {
    private static FinancialCharge charge() {
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        when(vehicle.getAssociate()).thenReturn(mock(Associate.class));
        return FinancialCharge.create(vehicle, FinancialChargeType.MONTHLY,
                FinancialBillingType.BOLETO, new BigDecimal("120.00"), LocalDate.of(2026, 11, 10));
    }

    @Test void eachVehicleChargeKeepsItsAssociatedConsultantAndPlate() {
        CommercialVehicle first = mock(CommercialVehicle.class);
        CommercialVehicle second = mock(CommercialVehicle.class);
        Consultant alice = mock(Consultant.class);
        Consultant bob = mock(Consultant.class);
        when(first.getAssociate()).thenReturn(mock(Associate.class));
        when(second.getAssociate()).thenReturn(mock(Associate.class));
        when(first.getConsultant()).thenReturn(alice);
        when(second.getConsultant()).thenReturn(bob);
        when(first.getPlate()).thenReturn("ABC1D23");
        when(second.getPlate()).thenReturn("XYZ9J87");
        FinancialCharge chargeA = FinancialCharge.create(first, FinancialChargeType.MONTHLY,
                FinancialBillingType.BOLETO, new BigDecimal("99.00"), LocalDate.of(2026, 11, 10));
        FinancialCharge chargeB = FinancialCharge.create(second, FinancialChargeType.MONTHLY,
                FinancialBillingType.BOLETO, new BigDecimal("79.00"), LocalDate.of(2026, 11, 10));
        assertSame(first, chargeA.getVehicle());
        assertSame(second, chargeB.getVehicle());
        assertSame(alice, chargeA.getConsultant());
        assertSame(bob, chargeB.getConsultant());
        assertNotEquals(chargeA.getId(), chargeB.getId());
    }

    @Test void delayedPendingEventCannotReopenAReceivedBoleto() {
        FinancialCharge charge = charge();
        charge.updateFromWebhook(FinancialChargeStatus.RECEIVED, null, null, null, null, null);
        charge.updateFromWebhook(FinancialChargeStatus.PENDING, null, null, null, null, null);
        charge.updateFromWebhook(FinancialChargeStatus.OVERDUE, null, null, null, null, null);
        assertEquals(FinancialChargeStatus.RECEIVED, charge.getStatus());
    }

    @Test void confirmedCannotRegressBeforeReceiving() {
        FinancialCharge charge = charge();
        charge.updateFromWebhook(FinancialChargeStatus.CONFIRMED, null, null, null, null, null);
        charge.updateFromWebhook(FinancialChargeStatus.OVERDUE, null, null, null, null, null);
        assertEquals(FinancialChargeStatus.CONFIRMED, charge.getStatus());
    }

    @Test void realRefundReversesReceivedAndIsTerminal() {
        FinancialCharge charge = charge();
        charge.updateFromWebhook(FinancialChargeStatus.RECEIVED, null, null, null, null, null);
        charge.updateFromWebhook(FinancialChargeStatus.REFUNDED, null, null, null, null, null);
        charge.updateFromWebhook(FinancialChargeStatus.RECEIVED, null, null, null, null, null);
        assertEquals(FinancialChargeStatus.REFUNDED, charge.getStatus());
    }
}
