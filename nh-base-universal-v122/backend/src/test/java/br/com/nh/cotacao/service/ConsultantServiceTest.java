package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.entity.CollaboratorRole;
import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.entity.InspectionAnalysisStage;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.ConsultantRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.repository.QuotationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsultantServiceTest {

    @Test
    void transferringConsultantToAnalystUpdatesOnlyPendingAnalysisAssignments() {
        ConsultantRepository consultantRepository = mock(ConsultantRepository.class);
        QuotationRepository quotationRepository = mock(QuotationRepository.class);
        InspectionRequestRepository inspectionRepository = mock(InspectionRequestRepository.class);
        CatalogChangeAuditRepository auditRepository = mock(CatalogChangeAuditRepository.class);
        PortalUserRepository portalUserRepository = mock(PortalUserRepository.class);

        Consultant consultant = Consultant.create("Gabriela Almeida", "ADMIN", CollaboratorRole.CONSULTANT);
        Consultant analyst = Consultant.create("Analista Teste", "ADMIN", CollaboratorRole.ANALYST);

        when(consultantRepository.findById(consultant.getId())).thenReturn(Optional.of(consultant));
        when(consultantRepository.findById(analyst.getId())).thenReturn(Optional.of(analyst));
        when(consultantRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
        when(consultantRepository.countByAssignedAnalyst_IdAndActiveTrue(analyst.getId())).thenReturn(4L);
        when(consultantRepository.saveAndFlush(consultant)).thenReturn(consultant);
        when(portalUserRepository.findByConsultantId(consultant.getId())).thenReturn(Optional.empty());
        when(quotationRepository.countByConsultantId(consultant.getId())).thenReturn(17L);
        when(inspectionRepository.countByConsultantId(consultant.getId())).thenReturn(9L);
        when(auditRepository.save(any(CatalogChangeAudit.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConsultantService service = new ConsultantService(
                consultantRepository,
                quotationRepository,
                inspectionRepository,
                auditRepository,
                portalUserRepository
        );

        service.update(
                consultant.getId(),
                consultant.getName(),
                true,
                CollaboratorRole.CONSULTANT,
                null,
                null,
                analyst.getId(),
                "admin"
        );

        verify(inspectionRepository).reassignPendingAnalysisForConsultant(
                eq(consultant.getId()),
                eq(analyst),
                eq(analyst.getName()),
                eq(List.of(InspectionAnalysisStage.ANALYST_QUEUE, InspectionAnalysisStage.ANALYST_PENDING)),
                eq(List.of(
                        InspectionRequestStatus.APPROVED,
                        InspectionRequestStatus.REJECTED,
                        InspectionRequestStatus.CANCELLED,
                        InspectionRequestStatus.EXPIRED
                ))
        );
        verify(inspectionRepository, never()).findAllByConsultant_IdOrderByCreatedAtDesc(any());
    }
}
