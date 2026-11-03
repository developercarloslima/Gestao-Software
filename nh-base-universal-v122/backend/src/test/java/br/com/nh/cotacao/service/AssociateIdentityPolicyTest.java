package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssociateIdentityPolicyTest {
    private static final String CPF = "52998224725";

    @Test void cpfCheckDigitsAndNameMustBothMatch() {
        assertEquals(CPF, AssociateIdentityPolicy.requireCpf("529.982.247-25"));
        assertThrows(IllegalArgumentException.class, () -> AssociateIdentityPolicy.requireCpf("111.111.111-11"));
        assertTrue(AssociateIdentityPolicy.namesMatch("João  da Silva", "JOAO DA SILVA"));
        assertFalse(AssociateIdentityPolicy.namesMatch("João da Silva", "João de Souza"));
    }

    @Test void distinctNamesOnSameCpfNeverUpdateExistingAssociate() {
        AssociateRepository associates = mock(AssociateRepository.class);
        CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
        Associate registered = Associate.create("Maria da Silva", CPF, "82999999999", null);
        when(associates.findByDocument(CPF)).thenReturn(Optional.of(registered));
        Quotation quotation = mock(Quotation.class);
        when(quotation.getCustomerCpf()).thenReturn(CPF);
        when(quotation.getCustomerName()).thenReturn("Maria Oliveira");

        CommercialFinanceService finance = finance(associates, vehicles);
        var conflict = assertThrows(IllegalArgumentException.class,
                () -> finance.registerQuotation(quotation, null, AdhesionPaymentMethod.BOLETO));
        assertTrue(conflict.getMessage().contains("CPF utilizado por outro associado"));
        assertEquals("Maria da Silva", registered.getName());
        verifyNoInteractions(vehicles);
        verify(associates, never()).save(any(Associate.class));
    }

    @Test void matchingCpfAutomaticallyReusesExistingAssociateAndVehicleStaysIndependent() {
        AssociateRepository associates = mock(AssociateRepository.class);
        CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
        Associate registered = Associate.create("João da Silva", CPF, "82988888888", null);
        when(associates.findByDocument(CPF)).thenReturn(Optional.of(registered));
        UUID quoteId = UUID.randomUUID();
        Quotation quotation = mock(Quotation.class);
        when(quotation.getCustomerCpf()).thenReturn(CPF);
        when(quotation.getCustomerName()).thenReturn("JOAO DA SILVA");
        when(quotation.getId()).thenReturn(quoteId);
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        when(vehicles.findByQuotation_Id(quoteId)).thenReturn(Optional.of(vehicle));
        when(vehicles.save(vehicle)).thenReturn(vehicle);

        CommercialVehicle result = finance(associates, vehicles)
                .registerQuotation(quotation, null, AdhesionPaymentMethod.BOLETO);
        assertSame(vehicle, result);
        verify(vehicle).setAssociate(registered);
        verify(associates, never()).save(any(Associate.class));
        assertEquals("João da Silva", registered.getName());
    }

    @Test void existingAsaasSubscriptionRequiresFinancialReviewBeforeReassociation() {
        AssociateRepository associates = mock(AssociateRepository.class);
        CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
        Associate target = Associate.create("João da Silva", CPF, null, null);
        Associate temporary = Associate.create("João da Silva", null, null, null);
        when(associates.findByDocument(CPF)).thenReturn(Optional.of(target));
        UUID id = UUID.randomUUID();
        Quotation quote = mock(Quotation.class);
        when(quote.getId()).thenReturn(id);
        when(quote.getCustomerCpf()).thenReturn(CPF);
        when(quote.getCustomerName()).thenReturn("João da Silva");
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        when(vehicle.getAssociate()).thenReturn(temporary);
        when(vehicle.getAsaasSubscriptionId()).thenReturn("sub_already_issued");
        when(vehicles.findByQuotation_Id(id)).thenReturn(Optional.of(vehicle));

        assertThrows(IllegalArgumentException.class,
                () -> finance(associates, vehicles).reconcileQuotationAssociate(quote));
        verify(vehicle, never()).setAssociate(any());
    }

    @Test void cpfIsOptionalDuringQuotationButRequiredForInspection() {
        AssociateRepository associates = mock(AssociateRepository.class);
        CommercialFinanceService finance = finance(associates, mock(CommercialVehicleRepository.class));
        assertThrows(IllegalArgumentException.class,
                () -> AssociateIdentityPolicy.requireCpf(null));
        assertEquals("NEW", finance.checkAssociateCpf(CPF, "Nome Novo").status());
        assertEquals("NEW", AssociateIdentityPolicy.matchStatus(Optional.empty(), "Nome Novo"));
    }

    private CommercialFinanceService finance(AssociateRepository associates, CommercialVehicleRepository vehicles) {
        return new CommercialFinanceService(associates, vehicles, mock(FinancialChargeRepository.class),
                mock(ConsultantRepository.class), mock(ConsultantWithdrawalRepository.class),
                mock(ConsultantWalletMovementRepository.class), mock(AsaasWebhookEventRepository.class),
                mock(FinancialProviderGateway.class), mock(ConsultantCommissionGuideService.class),
                new ObjectMapper());
    }
}
