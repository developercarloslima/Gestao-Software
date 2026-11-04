package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.SelfServiceChargeRequest;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AsaasAssociateConsultantSafetyTest {
    private final AssociateRepository associates = mock(AssociateRepository.class);
    private final CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
    private final FinancialChargeRepository charges = mock(FinancialChargeRepository.class);
    private final ConsultantRepository consultants = mock(ConsultantRepository.class);
    private final ConsultantWithdrawalRepository withdrawals = mock(ConsultantWithdrawalRepository.class);
    private final ConsultantWalletMovementRepository wallet = mock(ConsultantWalletMovementRepository.class);
    private final AsaasWebhookEventRepository webhookEvents = mock(AsaasWebhookEventRepository.class);
    private final FinancialProviderGateway provider = mock(FinancialProviderGateway.class);
    private final ConsultantCommissionGuideService guide = mock(ConsultantCommissionGuideService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private CommercialFinanceService service() {
        when(webhookEvents.saveAndFlush(any(AsaasWebhookEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new CommercialFinanceService(
                associates, vehicles, charges, consultants, withdrawals, wallet, webhookEvents, provider, guide, mapper);
    }

    private FinancialCharge charge(CommercialVehicle vehicle, FinancialChargeStatus status, LocalDate dueDate) {
        FinancialCharge result = FinancialCharge.create(vehicle, FinancialChargeType.MONTHLY,
                FinancialBillingType.BOLETO, new BigDecimal("120.00"), dueDate);
        if (status == FinancialChargeStatus.RECEIVED) result.markReceived(null);
        else if (status == FinancialChargeStatus.ERROR) result.markError();
        else if (status != FinancialChargeStatus.PENDING)
            result.updateFromWebhook(status, null, null, null, null, null);
        return result;
    }

    @Test void associateGetsOldestOutstandingMonthlyChargeFromAnnualBatch() {
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        when(vehicle.getAssociate()).thenReturn(mock(Associate.class));
        List<FinancialCharge> all = new ArrayList<>();
        LocalDate firstDue = LocalDate.of(2026, 11, 10);
        for (int i = 0; i < 12; i++) {
            all.add(charge(vehicle, FinancialChargeStatus.PENDING, firstDue.plusMonths(i)));
        }
        FinancialCharge dueNext = all.get(0);
        java.util.Collections.reverse(all); // Repositório entrega DESC; não podemos usar o primeiro.
        assertSame(dueNext, CommercialFinanceService.firstOutstandingMonthlyCharge(all).orElseThrow());
        dueNext.markReceived(null);
        assertEquals(firstDue.plusMonths(1),
                CommercialFinanceService.firstOutstandingMonthlyCharge(all).orElseThrow().getDueDate());
    }

    @Test void unknownOutcomePreventsNewAssociateMonthlyCharge() {
        UUID vehicleId = UUID.randomUUID(), associateId = UUID.randomUUID();
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        Associate associate = mock(Associate.class);
        when(vehicle.getAssociate()).thenReturn(associate);
        when(associate.getId()).thenReturn(associateId);
        when(vehicles.findById(vehicleId)).thenReturn(Optional.of(vehicle));
        when(charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId))
                .thenReturn(List.of(charge(vehicle, FinancialChargeStatus.ERROR, LocalDate.now())));
        assertThrows(IllegalStateException.class, () -> service().createAssociateSelfServiceCharge(
                associateId, vehicleId, new SelfServiceChargeRequest(FinancialBillingType.PIX)));
        verify(charges, never()).save(any(FinancialCharge.class));
        verify(provider, never()).createCharge(any(), any(), any(), any(), any(), any());
    }

    @Test void webhookAttachesToPreexistingMonthlyObligationInsteadOfDuplicatingIt() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        when(vehicle.getId()).thenReturn(vehicleId);
        when(vehicle.getAssociate()).thenReturn(mock(Associate.class));
        FinancialCharge existing = charge(vehicle, FinancialChargeStatus.PENDING, LocalDate.of(2026, 12, 10));
        String externalReference = existing.getExternalReference();
        when(charges.findByExternalReference(externalReference)).thenReturn(Optional.of(existing));
        when(charges.save(existing)).thenReturn(existing);
        String json = "{\"id\":\"evt_link\",\"event\":\"PAYMENT_CREATED\",\"payment\":{\"id\":\"pay_link\",\"status\":\"PENDING\",\"externalReference\":\"" +
                externalReference + "\",\"billingType\":\"BOLETO\"}}";
        service().handleAsaasWebhook(mapper.readTree(json));
        assertEquals("pay_link", existing.getProviderPaymentId());
        verify(charges).findByExternalReference(externalReference);
        verify(charges).save(existing);
        verify(charges, never()).save(argThat(c -> c != existing));
    }

    @Test void delayedReceivedEventCannotReopenRefundedCommissionClosing() throws Exception {
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        when(vehicle.getAssociate()).thenReturn(mock(Associate.class));
        FinancialCharge existing = charge(vehicle, FinancialChargeStatus.REFUNDED, LocalDate.of(2026, 10, 10));
        existing.applyProvider("pay_refunded", null, null, null, null, null, null, null);
        when(charges.findByProviderPaymentId("pay_refunded")).thenReturn(Optional.of(existing));
        when(charges.save(existing)).thenReturn(existing);
        service().handleAsaasWebhook(mapper.readTree(
                "{\"id\":\"evt_late_paid\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_refunded\",\"status\":\"RECEIVED\"}}"));
        assertEquals(FinancialChargeStatus.REFUNDED, existing.getStatus());
        assertEquals(FinancialChargeType.MONTHLY, existing.getChargeType());
        verify(charges, never()).countByVehicle_IdAndChargeTypeAndStatus(any(), any(), any());
    }

    @Test void delayedTransferFailureCannotReverseAlreadyPaidWithdrawal() throws Exception {
        Consultant c = mock(Consultant.class);
        ConsultantWithdrawal paid = ConsultantWithdrawal.createPix(c, new BigDecimal("100.00"),
                new BigDecimal("2.00"), PixKeyType.CPF, "12345678909");
        paid.processing("trans_done");
        paid.paid();
        when(withdrawals.findByProviderTransferId("trans_done")).thenReturn(Optional.of(paid));
        service().handleAsaasWebhook(mapper.readTree(
                "{\"id\":\"evt_late_failed\",\"event\":\"TRANSFER_FAILED\",\"transfer\":{\"id\":\"trans_done\",\"failReason\":\"late\"}}"));
        assertEquals(WithdrawalStatus.PAID, paid.getStatus());
        verify(wallet, never()).save(any(ConsultantWalletMovement.class));
    }

    @Test void requestsForSameConsultantUsePessimisticBalanceLock() throws Exception {
        Lock lock = ConsultantRepository.class.getMethod("findByIdForUpdate", UUID.class)
                .getAnnotation(Lock.class);
        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }
}
