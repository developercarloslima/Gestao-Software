package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssociateBoletoIssuanceTest {
    private final CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
    private final FinancialChargeRepository charges = mock(FinancialChargeRepository.class);
    private final FinancialProviderGateway provider = mock(FinancialProviderGateway.class);
    private final CommercialFinanceService finance = new CommercialFinanceService(
            mock(AssociateRepository.class), vehicles, charges,
            mock(ConsultantRepository.class), mock(ConsultantWithdrawalRepository.class),
            mock(ConsultantWalletMovementRepository.class), mock(AsaasWebhookEventRepository.class),
            provider, mock(ConsultantCommissionGuideService.class), new ObjectMapper());
    private final UUID associateId = UUID.randomUUID();
    private final UUID vehicleId = UUID.randomUUID();
    private final UUID quotationId = UUID.randomUUID();
    private final Associate associate = mock(Associate.class);
    private final CommercialVehicle vehicle = mock(CommercialVehicle.class);
    private final Quotation quotation = mock(Quotation.class);
    private FinancialCharge charge;

    @BeforeEach
    void setup() {
        when(associate.getId()).thenReturn(associateId);
        when(associate.getDocument()).thenReturn("52998224725");
        when(associate.getAsaasCustomerId()).thenReturn("cus_existing");
        when(vehicle.getId()).thenReturn(vehicleId);
        when(vehicle.getAssociate()).thenReturn(associate);
        when(vehicle.getQuotation()).thenReturn(quotation);
        when(vehicle.getStatus()).thenReturn(CommercialVehicleStatus.ACTIVE);
        when(vehicle.getModel()).thenReturn("Civic");
        when(quotation.getId()).thenReturn(quotationId);
        when(vehicles.hasAcceptedContract(quotationId, "52998224725", InspectionRequestStatus.APPROVED)).thenReturn(true);
        when(provider.code()).thenReturn("ASAAS");
        when(provider.enabled()).thenReturn(true);
        when(charges.save(any(FinancialCharge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        charge = FinancialCharge.create(vehicle, FinancialChargeType.MONTHLY, FinancialBillingType.BOLETO,
                new BigDecimal("270.00"), LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusMonths(2));
        when(charges.findByIdForIssuance(charge.getId())).thenReturn(Optional.of(charge));
    }

    @Test
    void issuesSelectedTitleOnceAndReturnsPrintableBoletoUrl() {
        when(provider.createCharge(eq("cus_existing"), eq(FinancialBillingType.BOLETO), any(),
                any(), anyString(), eq(charge.getExternalReference())))
                .thenReturn(new FinancialProvider.PaymentData("pay_test", "PENDING",
                        "https://sandbox.asaas.com/i/test", "https://sandbox.asaas.com/b/pdf/test",
                        "123", "456", null, null, null, charge.getExternalReference()));

        var first = finance.issueAssociateBoleto(associateId, charge.getId());
        var second = finance.issueAssociateBoleto(associateId, charge.getId());

        assertEquals("https://sandbox.asaas.com/b/pdf/test", first.bankSlipUrl());
        assertEquals(first.bankSlipUrl(), second.bankSlipUrl());
        assertEquals("pay_test", charge.getProviderPaymentId());
        verify(provider, times(1)).createCharge(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesDifferentAssociateBeforeCallingProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> finance.issueAssociateBoleto(UUID.randomUUID(), charge.getId()));
        verify(provider, never()).createCharge(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesBeforeContractAcceptance() {
        when(vehicles.hasAcceptedContract(quotationId, "52998224725", InspectionRequestStatus.APPROVED)).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> finance.issueAssociateBoleto(associateId, charge.getId()));
        verify(provider, never()).createCharge(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesWhenFinancialGatewayIsDisabled() {
        when(provider.enabled()).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> finance.issueAssociateBoleto(associateId, charge.getId()));
        verify(provider, never()).createCharge(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesRetryAfterUncertainProviderErrorToAvoidDuplicatePayment() {
        charge.markError();
        assertThrows(IllegalStateException.class,
                () -> finance.issueAssociateBoleto(associateId, charge.getId()));
        verify(provider, never()).createCharge(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void alignsExpiredUnissuedAdhesionToCurrentDateOnlyOnIssuance() {
        charge = FinancialCharge.create(vehicle, FinancialChargeType.ADHESION,
                FinancialBillingType.BOLETO, new BigDecimal("100.00"), LocalDate.now().minusDays(2));
        when(charges.findByIdForIssuance(charge.getId())).thenReturn(Optional.of(charge));
        when(provider.createCharge(anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(new FinancialProvider.PaymentData("pay_adhesion", "PENDING",
                        "https://sandbox.asaas.com/i/adhesion", "https://sandbox.asaas.com/b/pdf/adhesion",
                        null, null, null, null, null, charge.getExternalReference()));

        finance.issueAssociateBoleto(associateId, charge.getId());
        assertEquals(LocalDate.now(ZoneId.of("America/Sao_Paulo")), charge.getDueDate());
        verify(provider).createCharge(anyString(), eq(FinancialBillingType.BOLETO), any(),
                eq(charge.getDueDate()), anyString(), eq(charge.getExternalReference()));
    }
}
