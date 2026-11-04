package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AcceptedAssociateLocalBillingTest {
    private final CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
    private final FinancialChargeRepository charges = mock(FinancialChargeRepository.class);
    private final FinancialProviderGateway provider = mock(FinancialProviderGateway.class);
    private final CommercialFinanceService finance = new CommercialFinanceService(
            mock(AssociateRepository.class), vehicles, charges,
            mock(ConsultantRepository.class), mock(ConsultantWithdrawalRepository.class),
            mock(ConsultantWalletMovementRepository.class), mock(AsaasWebhookEventRepository.class),
            provider, mock(ConsultantCommissionGuideService.class), new ObjectMapper());

    private final UUID vehicleId = UUID.randomUUID();
    private final UUID quotationId = UUID.randomUUID();
    private final CommercialVehicle vehicle = mock(CommercialVehicle.class);
    private final Quotation quotation = mock(Quotation.class);
    private final Associate associate = mock(Associate.class);

    private void prepareVehicle(boolean contractAccepted) {
        when(vehicles.findByIdForBillingUpdate(vehicleId)).thenReturn(Optional.of(vehicle));
        when(vehicle.getId()).thenReturn(vehicleId);
        when(vehicle.getStatus()).thenReturn(CommercialVehicleStatus.ACTIVE);
        when(vehicle.getQuotation()).thenReturn(quotation);
        when(quotation.getId()).thenReturn(quotationId);
        when(vehicle.getAssociate()).thenReturn(associate);
        when(associate.getDocument()).thenReturn("52998224725");
        when(vehicles.hasAcceptedContract(quotationId, "52998224725", InspectionRequestStatus.APPROVED))
                .thenReturn(contractAccepted);
        when(vehicle.getAdhesionValue()).thenReturn(new BigDecimal("100.00"));
        when(vehicle.getAdhesionPaymentMethod()).thenReturn(AdhesionPaymentMethod.BOLETO);
        when(vehicle.getMonthlyValue()).thenReturn(new BigDecimal("270.00"));
        when(vehicle.getRecurringBillingType()).thenReturn(FinancialBillingType.BOLETO);
        when(vehicle.getFirstBillingDueDate()).thenReturn(LocalDate.now().plusMonths(1));
        when(provider.code()).thenReturn("ASAAS");
        when(charges.save(any(FinancialCharge.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void recordsOneAdhesionAndTwelveFutureInstallmentsWithoutCallingAsaas() {
        prepareVehicle(true);
        when(charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId))
                .thenReturn(List.of());
        finance.ensureAcceptedLocalBilling(vehicleId);

        var captured = org.mockito.ArgumentCaptor.forClass(FinancialCharge.class);
        verify(charges, times(13)).save(captured.capture());
        long adhesion = captured.getAllValues().stream()
                .filter(c -> c.getChargeType() == FinancialChargeType.ADHESION).count();
        long monthly = captured.getAllValues().stream()
                .filter(c -> c.getChargeType() == FinancialChargeType.MONTHLY).count();
        assertEquals(1, adhesion);
        assertEquals(12, monthly);
        assertEquals(new BigDecimal("270.00"), captured.getAllValues().stream()
                .filter(c -> c.getChargeType() == FinancialChargeType.MONTHLY).findFirst().orElseThrow().getValue());
        verify(vehicle).enableAdvanceBillingAfterAcceptance();
        verify(vehicles).save(vehicle);
        verify(provider, never()).createCharge(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotCreateChargesBeforeContractIsAccepted() {
        prepareVehicle(false);
        finance.ensureAcceptedLocalBilling(vehicleId);
        verify(charges, never()).save(any());
        verify(vehicles, never()).save(any());
    }

    @Test
    void doesNotDuplicateExistingFinancialHistory() {
        prepareVehicle(true);
        FinancialCharge previous = FinancialCharge.create(vehicle, FinancialChargeType.MONTHLY,
                FinancialBillingType.BOLETO, new BigDecimal("270.00"), LocalDate.now().plusMonths(1));
        when(charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId)).thenReturn(List.of(previous));
        finance.ensureAcceptedLocalBilling(vehicleId);
        verify(charges, never()).save(any());
        verify(vehicles, never()).save(any());
    }
}
