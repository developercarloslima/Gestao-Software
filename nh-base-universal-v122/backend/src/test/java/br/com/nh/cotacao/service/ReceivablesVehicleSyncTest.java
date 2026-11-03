package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import br.com.nh.cotacao.security.PortalRole;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReceivablesVehicleSyncTest {
    @Test void onlyExistingChargesForSelectedPlateAreReconciledWithoutCreatingNewPayments() {
        UUID vehicleId = UUID.randomUUID();
        UUID chargeId = UUID.randomUUID();
        UUID associateId = UUID.randomUUID();
        CommercialVehicle v = mock(CommercialVehicle.class);
        Associate associate = mock(Associate.class);
        FinancialCharge charge = mock(FinancialCharge.class);
        CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
        FinancialChargeRepository charges = mock(FinancialChargeRepository.class);
        FinancialProviderGateway provider = mock(FinancialProviderGateway.class);
        CommercialFinanceService finance = mock(CommercialFinanceService.class);

        when(vehicles.findById(vehicleId)).thenReturn(Optional.of(v));
        when(v.getId()).thenReturn(vehicleId);
        when(v.getAssociate()).thenReturn(associate);
        when(associate.getId()).thenReturn(associateId);
        when(associate.getName()).thenReturn("Associado Teste");
        when(v.getPlate()).thenReturn("ABC1D23");
        when(v.getStatus()).thenReturn(CommercialVehicleStatus.ACTIVE);
        when(v.getRecurringBillingType()).thenReturn(FinancialBillingType.BOLETO);
        when(v.getFirstBillingDueDate()).thenReturn(LocalDate.now().plusDays(10));
        when(v.getBillingDueDay()).thenReturn(10);
        when(v.getMonthlyValue()).thenReturn(new BigDecimal("99.90"));

        when(charge.getId()).thenReturn(chargeId);
        when(charge.getBillingType()).thenReturn(FinancialBillingType.BOLETO);
        when(charge.getChargeType()).thenReturn(FinancialChargeType.MONTHLY);
        when(charge.getStatus()).thenReturn(FinancialChargeStatus.PENDING);
        when(charge.getProviderPaymentId()).thenReturn("pay_sandbox123");
        when(charge.getDueDate()).thenReturn(LocalDate.now().plusDays(10));
        when(charge.getCreatedAt()).thenReturn(OffsetDateTime.now());
        when(charge.getValue()).thenReturn(new BigDecimal("99.90"));
        when(charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId))
                .thenReturn(List.of(charge));
        when(provider.enabled()).thenReturn(true);

        ReceivablesService service = new ReceivablesService(vehicles, charges, provider, finance);
        ReceivablesService.VehicleSync result = service.synchronizeVehicle(vehicleId);
        assertEquals(1, result.checked());
        assertEquals(0, result.failed());
        assertEquals("ABC1D23", result.bills().vehicle().plate());
        verify(finance).refreshCharge(chargeId, null, PortalRole.ADMIN);
        verifyNoMoreInteractions(finance);
        verify(provider, atLeastOnce()).enabled();
        verifyNoMoreInteractions(provider);
    }
}
