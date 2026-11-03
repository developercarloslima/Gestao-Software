package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReceivablesSecondCopyTest {
    private final CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
    private final FinancialChargeRepository charges = mock(FinancialChargeRepository.class);
    private final FinancialProviderGateway provider = mock(FinancialProviderGateway.class);
    private final CommercialFinanceService finance = mock(CommercialFinanceService.class);
    private final ReceivablesService service = new ReceivablesService(vehicles, charges, provider, finance);

    private FinancialCharge charge(LocalDate due) {
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        Associate associate = mock(Associate.class);
        when(vehicle.getId()).thenReturn(UUID.randomUUID());
        when(vehicle.getAssociate()).thenReturn(associate);
        FinancialCharge created = FinancialCharge.create(vehicle, FinancialChargeType.MONTHLY,
                FinancialBillingType.BOLETO, new BigDecimal("125.00"), due);
        created.applyProvider("pay_existing123", null, "https://www.asaas.com/i/existing",
                "https://www.asaas.com/b/pdf", "old-code", "old-barcode", null, null);
        when(charges.findById(created.getId())).thenReturn(Optional.of(created));
        when(provider.enabled()).thenReturn(true);
        return created;
    }

    @Test void updatingOverdueKeepsOriginalAsaasPaymentAndInvalidatesOldBarCode() {
        FinancialCharge existing = charge(LocalDate.now().minusDays(8));
        LocalDate next = LocalDate.now().plusDays(5);
        when(provider.updatePaymentDueDate("pay_existing123", next)).thenReturn(
                new FinancialProvider.PaymentData("pay_existing123", "PENDING",
                        "https://www.asaas.com/i/existing", "https://www.asaas.com/b/new",
                        "new-code", null, null, null, null, null));
        var copy = service.secondCopy(existing.getId(), new ReceivablesService.SecondCopyRequest(next));
        assertEquals("pay_existing123", existing.getProviderPaymentId());
        assertEquals(next, existing.getDueDate());
        assertEquals("new-code", copy.identificationField());
        assertNull(existing.getBarCode());
        assertEquals(FinancialChargeStatus.PENDING, existing.getStatus());
        verify(provider).updatePaymentDueDate("pay_existing123", next);
        verify(provider, never()).createCharge(anyString(), any(), any(), any(), any(), any());
    }

    @Test void overdueMustHaveExplicitNewDueDate() {
        FinancialCharge existing = charge(LocalDate.now().minusDays(1));
        assertThrows(IllegalArgumentException.class,
                () -> service.secondCopy(existing.getId(), null));
        verify(provider, never()).updatePaymentDueDate(anyString(), any());
    }

    @Test void paidChargeMustNotBeReissued() {
        FinancialCharge existing = charge(LocalDate.now().minusDays(10));
        existing.markReceived(OffsetDateTime.now());
        assertThrows(IllegalArgumentException.class,
                () -> service.secondCopy(existing.getId(), null));
        verify(provider, never()).updatePaymentDueDate(anyString(), any());
    }
}
