package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.Associate;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.repository.AssociateRepository;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionAcceptanceAccountWorkflowTest {
    private static final String CPF = "52998224725";
    private final AssociateRepository associates = mock(AssociateRepository.class);
    private final InspectionRequestRepository inspections = mock(InspectionRequestRepository.class);
    private final PortalUserService users = mock(PortalUserService.class);
    private final InspectionAcceptanceWorkflowService workflow = new InspectionAcceptanceWorkflowService(
            mock(JdbcTemplate.class), inspections, mock(CatalogChangeAuditRepository.class),
            users, associates);

    @Test
    void sendingApprovalPreparesButDoesNotActivateAccount() {
        UUID id = UUID.randomUUID();
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        InspectionRequest inspection = mock(InspectionRequest.class);
        when(inspection.getStatus()).thenReturn(InspectionRequestStatus.APPROVED);
        when(inspection.getRegistrationCompletedAt()).thenReturn(OffsetDateTime.now());
        when(inspection.getCpf()).thenReturn(CPF);
        when(inspection.getAssociateName()).thenReturn("Maria Silva");
        when(inspection.getAssociate()).thenReturn(associate);
        when(associates.findByDocument(CPF)).thenReturn(Optional.of(associate));
        when(users.prepareAssociateAccountForAcceptance(associate.getId())).thenReturn(CPF);

        workflow.prepareAssociatePortalAccess(inspection);

        verify(users).prepareAssociateAccountForAcceptance(associate.getId());
        verify(users, never()).activateAssociateAccountAfterAcceptance(any());
    }

    @Test
    void refusesToLinkDifferentNameToRegisteredCpf() {
        Associate associate = Associate.create("Outra Pessoa", CPF, null, null);
        InspectionRequest inspection = mock(InspectionRequest.class);
        when(inspection.getStatus()).thenReturn(InspectionRequestStatus.APPROVED);
        when(inspection.getRegistrationCompletedAt()).thenReturn(OffsetDateTime.now());
        when(inspection.getCpf()).thenReturn(CPF);
        when(inspection.getAssociateName()).thenReturn("Maria Silva");
        when(associates.findByDocument(CPF)).thenReturn(Optional.of(associate));

        assertThrows(IllegalArgumentException.class,
                () -> workflow.prepareAssociatePortalAccess(inspection));
        verifyNoInteractions(users);
    }


    @Test
    void activatesWhenContractAcceptedEvenIfAdditionalTermsRemainPending() {
        UUID id = UUID.randomUUID();
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        InspectionRequest inspection = mock(InspectionRequest.class);
        when(inspection.getId()).thenReturn(id);
        when(inspection.getStatus()).thenReturn(InspectionRequestStatus.APPROVED);
        when(inspection.getRegistrationCompletedAt()).thenReturn(OffsetDateTime.now());
        when(inspection.getCpf()).thenReturn(CPF);
        when(inspection.getAssociateName()).thenReturn("Maria Silva");
        when(inspection.getAssociate()).thenReturn(associate);
        when(associates.findByDocument(CPF)).thenReturn(Optional.of(associate));
        when(inspections.findById(id)).thenReturn(Optional.of(inspection));
        when(inspection.getAcceptedAt()).thenReturn(OffsetDateTime.now());

        workflow.activateAssociatePortalAccessIfComplete(id);

        verify(users).prepareAssociateAccountForAcceptance(associate.getId());
        verify(users).activateAssociateAccountAfterAcceptance(associate.getId());
    }

    @Test
    void additionalTermsWithoutContractAcceptanceKeepLoginInactive() {
        UUID id = UUID.randomUUID();
        Associate associate = Associate.create("Maria Silva", CPF, null, null);
        InspectionRequest inspection = mock(InspectionRequest.class);
        when(inspection.getId()).thenReturn(id);
        when(inspection.getStatus()).thenReturn(InspectionRequestStatus.APPROVED);
        when(inspection.getRegistrationCompletedAt()).thenReturn(OffsetDateTime.now());
        when(inspection.getCpf()).thenReturn(CPF);
        when(inspection.getAssociateName()).thenReturn("Maria Silva");
        when(inspection.getAssociate()).thenReturn(associate);
        when(associates.findByDocument(CPF)).thenReturn(Optional.of(associate));
        when(inspections.findById(id)).thenReturn(Optional.of(inspection));

        workflow.activateAssociatePortalAccessIfComplete(id);

        verify(users).prepareAssociateAccountForAcceptance(associate.getId());
        verify(users, never()).activateAssociateAccountAfterAcceptance(any());
    }

    @Test
    void approvedWithoutDigitalSignaturesDoesNotActivateAccount() {
        UUID id = UUID.randomUUID();
        InspectionRequest inspection = mock(InspectionRequest.class);
        when(inspection.getId()).thenReturn(id);
        when(inspection.getStatus()).thenReturn(InspectionRequestStatus.APPROVED);
        when(inspections.findById(id)).thenReturn(Optional.of(inspection));

        workflow.activateAssociatePortalAccessIfComplete(id);

        verifyNoInteractions(users);
        verifyNoInteractions(associates);
    }
}
