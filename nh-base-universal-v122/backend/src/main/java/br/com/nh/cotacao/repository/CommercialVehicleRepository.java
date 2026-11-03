package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.CommercialVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import br.com.nh.cotacao.entity.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommercialVehicleRepository extends JpaRepository<CommercialVehicle, UUID> {
    @Query("""
        select v from CommercialVehicle v join v.associate a
        where v.status <> :draft
        and (
          (:searched = false and exists (
            select c.id from FinancialCharge c where c.vehicle = v
            and c.billingType = :boleto and c.status in :openStatuses
            and c.dueDate < :today
          ))
          or (:searched = true and (
            a.normalizedName like :pattern escape '!'
            or a.document like :documentPattern escape '!'
            or lower(v.plate) like :platePattern escape '!'
          ))
        )
        """)
    Page<CommercialVehicle> receivables(
        @Param("draft") CommercialVehicleStatus draft,
        @Param("searched") boolean searched,
        @Param("boleto") FinancialBillingType boleto,
        @Param("openStatuses") List<FinancialChargeStatus> openStatuses,
        @Param("today") java.time.LocalDate today,
        @Param("pattern") String pattern,
        @Param("documentPattern") String documentPattern,
        @Param("platePattern") String platePattern,
        Pageable pageable
    );
    Optional<CommercialVehicle> findByQuotation_Id(UUID quotationId);
    Optional<CommercialVehicle> findByAsaasSubscriptionId(String asaasSubscriptionId);
    List<CommercialVehicle> findAllByAssociate_IdOrderByCreatedAtDesc(UUID associateId);
    List<CommercialVehicle> findAllByConsultant_IdOrderByCreatedAtDesc(UUID consultantId);
    List<CommercialVehicle> findAllByOrderByCreatedAtDesc();
}
