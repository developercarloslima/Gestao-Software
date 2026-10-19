package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.entity.InspectionAnalysisStage;
import br.com.nh.cotacao.entity.Consultant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InspectionRequestRepository extends JpaRepository<InspectionRequest, UUID> {
    long countByConsultantId(UUID consultantId);
    long countByCreatedAtBefore(OffsetDateTime cutoff);
    long countByStatus(InspectionRequestStatus status);

    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    Optional<InspectionRequest> findByPublicToken(String publicToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from InspectionRequest request where request.publicToken = :publicToken")
    Optional<InspectionRequest> findByPublicTokenForUpdate(@Param("publicToken") String publicToken);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inspection from InspectionRequest inspection where inspection.id = :id")
    Optional<InspectionRequest> findForAcceptanceReapproval(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    Optional<InspectionRequest> findByQuotation_Id(UUID quotationId);

    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    List<InspectionRequest> findTop300ByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    List<InspectionRequest> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    List<InspectionRequest> findAllByConsultant_IdOrderByCreatedAtDesc(UUID consultantId);

    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    List<InspectionRequest> findAllByConsultantNameIgnoreCaseOrderByCreatedAtDesc(String consultantName);

    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    List<InspectionRequest> findAllByAssignedAnalyst_IdOrderByCreatedAtDesc(UUID analystId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update InspectionRequest i
               set i.assignedAnalyst = :analyst,
                   i.assignedAnalystName = :analystName
             where i.consultant.id = :consultantId
               and (i.analysisStage is null or i.analysisStage in (:pendingStages))
               and i.status not in (:finalStatuses)
            """)
    int reassignPendingAnalysisForConsultant(
            @Param("consultantId") UUID consultantId,
            @Param("analyst") Consultant analyst,
            @Param("analystName") String analystName,
            @Param("pendingStages") List<InspectionAnalysisStage> pendingStages,
            @Param("finalStatuses") List<InspectionRequestStatus> finalStatuses
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from InspectionRequest i where i.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from InspectionRequest i where i.quotation.id in :quotationIds")
    int deleteByQuotationIds(@Param("quotationIds") List<UUID> quotationIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from InspectionRequest i where i.status <> :protectedStatus")
    int deleteAllExceptStatus(@Param("protectedStatus") InspectionRequestStatus protectedStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from InspectionRequest i")
    int deleteAllInspections();

    @Override
    @EntityGraph(attributePaths = {"assets", "consultant", "quotation", "assignedAnalyst"})
    Optional<InspectionRequest> findById(UUID id);
}
