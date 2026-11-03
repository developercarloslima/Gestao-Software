package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicAcceptancePaymentServiceTest {
    private final InspectionRequestRepository inspections = mock(InspectionRequestRepository.class);
    private final CommercialVehicleRepository vehicles = mock(CommercialVehicleRepository.class);
    private final FinancialChargeRepository charges = mock(FinancialChargeRepository.class);
    private final FinancialProviderGateway provider = mock(FinancialProviderGateway.class);
    private final PublicAcceptancePaymentService service =
            new PublicAcceptancePaymentService(inspections, vehicles, charges, provider);

    @Test
    void refusesToShowFinancialDataBeforeWebauthnAcceptance() {
        InspectionRequest inspection = mock(InspectionRequest.class);
        when(inspections.findByPublicToken("safe-token")).thenReturn(Optional.of(inspection));

        assertThrows(IllegalArgumentException.class, () -> service.findAdhesion("safe-token"));
        verifyNoInteractions(vehicles, charges);
    }

    @Test
    void returnsOneLinkedAdhesionPaymentWithPixAndBoletoWithoutCreatingCharges() {
        InspectionRequest inspection = mock(InspectionRequest.class);
        Quotation quotation = mock(Quotation.class);
        CommercialVehicle vehicle = mock(CommercialVehicle.class);
        Associate associate = mock(Associate.class);
        FinancialCharge charge = mock(FinancialCharge.class);
        UUID quoteId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        when(inspections.findByPublicToken("safe-token")).thenReturn(Optional.of(inspection));
        when(inspection.getAcceptedAt()).thenReturn(OffsetDateTime.now());
        when(inspection.getStatus()).thenReturn(InspectionRequestStatus.APPROVED);
        when(inspection.getQuotation()).thenReturn(quotation);
        when(inspection.getCpf()).thenReturn("52998224725");
        when(quotation.getId()).thenReturn(quoteId);
        when(vehicles.findByQuotation_Id(quoteId)).thenReturn(Optional.of(vehicle));
        when(vehicle.getId()).thenReturn(vehicleId);
        when(vehicle.getAssociate()).thenReturn(associate);
        when(associate.getDocument()).thenReturn("52998224725");
        when(charges.findFirstByVehicle_IdAndChargeTypeOrderByCreatedAtAsc(
                vehicleId, FinancialChargeType.ADHESION)).thenReturn(Optional.of(charge));
        when(charges.findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(vehicleId)).thenReturn(List.of(charge));
        when(provider.enabled()).thenReturn(true);
        when(charge.getStatus()).thenReturn(FinancialChargeStatus.PENDING);
        when(charge.getDueDate()).thenReturn(LocalDate.now().plusDays(3));
        when(charge.getProviderPaymentId()).thenReturn("pay-valid");
        when(charge.getGrossValue()).thenReturn(new BigDecimal("102.00"));
        when(charge.getPixPayload()).thenReturn("pix-copia-cola");
        when(charge.getIdentificationField()).thenReturn("codigo-boleto");
        when(charge.getInvoiceUrl()).thenReturn("https://sandbox.asaas.com/i/example");

        var response = service.findAdhesion("safe-token");

        assertTrue(response.available());
        assertEquals("pix-copia-cola", response.pixPayload());
        assertEquals("codigo-boleto", response.identificationField());
        assertEquals(new BigDecimal("102.00"), response.amount());
        verify(charges, never()).save(any());
    }

    @Test
    void acceptedWithoutLinkedQuotationShowsSafeEmptyState() {
        InspectionRequest inspection = mock(InspectionRequest.class);
        when(inspections.findByPublicToken("safe-token")).thenReturn(Optional.of(inspection));
        when(inspection.getAcceptedAt()).thenReturn(OffsetDateTime.now());
        when(inspection.getStatus()).thenReturn(InspectionRequestStatus.APPROVED);
        when(provider.enabled()).thenReturn(false);

        var response = service.findAdhesion("safe-token");

        assertFalse(response.available());
        assertEquals("NOT_ISSUED", response.status());
        assertNull(response.pixPayload());
        verifyNoInteractions(vehicles, charges);
    }
}
