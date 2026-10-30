package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceOfficeIntegrityTest {
    @Test
    void pixCannotBeMarkedPaidByManualFinanceOperation() {
        assertThrows(IllegalArgumentException.class, () ->
                FinanceOfficeService.assertManualPaidAllowed(ConsultantPayoutMethod.PIX, WithdrawalStatus.PROCESSING));
    }

    @Test
    void tedMustBeApprovedBeforeManualSettlement() {
        assertThrows(IllegalArgumentException.class, () ->
                FinanceOfficeService.assertManualPaidAllowed(ConsultantPayoutMethod.TED, WithdrawalStatus.REQUESTED));
        assertDoesNotThrow(() ->
                FinanceOfficeService.assertManualPaidAllowed(ConsultantPayoutMethod.TED, WithdrawalStatus.PROCESSING));
    }

    @Test
    void uncertainAsaasTransferRetainsFundsForManualReconciliation() {
        ConsultantWithdrawalRepository withdrawals = mock(ConsultantWithdrawalRepository.class);
        ConsultantWithdrawal withdrawal = mock(ConsultantWithdrawal.class);
        Consultant consultant = mock(Consultant.class);
        AsaasClient asaas = mock(AsaasClient.class);
        UUID id = UUID.randomUUID();

        when(withdrawals.findByIdForUpdate(id)).thenReturn(Optional.of(withdrawal));
        when(withdrawal.getStatus()).thenReturn(WithdrawalStatus.REQUESTED);
        when(withdrawal.getPayoutMethod()).thenReturn(ConsultantPayoutMethod.PIX);
        when(withdrawal.getPixKeyType()).thenReturn(PixKeyType.CPF);
        when(withdrawal.getPixKey()).thenReturn("12345678909");
        when(withdrawal.getAmount()).thenReturn(new BigDecimal("100.00"));
        when(withdrawal.getNetAmount()).thenReturn(new BigDecimal("98.00"));
        when(withdrawal.getConsultant()).thenReturn(consultant);
        when(consultant.getName()).thenReturn("Consultor teste");
        when(asaas.enabled()).thenReturn(true);
        when(asaas.balance()).thenReturn(new BigDecimal("200.00"));
        when(asaas.transferPix(any(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("timeout"));

        FinanceOfficeService service = new FinanceOfficeService(
                mock(JdbcTemplate.class), mock(ConsultantRepository.class), withdrawals,
                mock(ConsultantCommissionGuideService.class), asaas);
        assertThrows(FinanceOfficeService.AsaasTransferFailure.class,
                () -> service.processWithdrawal(id, null));

        verify(withdrawal).processing(isNull());
        verify(withdrawal).financeNote(contains("Conferir no Asaas"));
        verify(withdrawals).save(withdrawal);
        verify(withdrawals).findByIdForUpdate(id);
    }

    @Test
    void withdrawalIsLockedBeforeCallingExternalPaymentApi() throws Exception {
        Lock lock = ConsultantWithdrawalRepository.class
                .getMethod("findByIdForUpdate", UUID.class)
                .getAnnotation(Lock.class);
        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
    }

    @Test
    void transferUncertaintyIsCommittedEvenWhenRequestReturnsError() throws Exception {
        Transactional annotation = FinanceOfficeService.class
                .getMethod("processWithdrawal", UUID.class, String.class)
                .getAnnotation(Transactional.class);
        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.noRollbackFor()).contains(FinanceOfficeService.AsaasTransferFailure.class));
    }
}
