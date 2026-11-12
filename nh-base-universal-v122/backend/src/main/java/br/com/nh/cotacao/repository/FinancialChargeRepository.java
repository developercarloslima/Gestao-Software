package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialChargeRepository extends JpaRepository<FinancialCharge, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from FinancialCharge c where c.id = :chargeId")
    Optional<FinancialCharge> findByIdForIssuance(@org.springframework.data.repository.query.Param("chargeId") UUID chargeId);
    Optional<FinancialCharge> findByProviderPaymentId(String providerPaymentId);
    Optional<FinancialCharge> findByExternalReference(String externalReference);
    List<FinancialCharge> findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(UUID vehicleId);
    long countByVehicle_IdAndChargeType(UUID vehicleId, FinancialChargeType type);
    long countByVehicle_IdAndChargeTypeAndStatus(UUID vehicleId, FinancialChargeType type, FinancialChargeStatus status);
    long countByConsultant_IdAndChargeTypeAndStatus(UUID consultantId, FinancialChargeType type, FinancialChargeStatus status);
    boolean existsByVehicle_IdAndChargeType(UUID vehicleId, FinancialChargeType type);
    boolean existsByVehicle_IdAndDueDateAndChargeTypeIn(UUID vehicleId, java.time.LocalDate dueDate,
                                                         java.util.Collection<FinancialChargeType> types);
    @org.springframework.data.jpa.repository.Query(value="""
        SELECT c.* FROM financial_charges c
        JOIN commercial_vehicles v ON v.id=c.vehicle_id
        WHERE v.advance_billing_enabled=TRUE AND v.asaas_subscription_id IS NULL
          AND c.charge_type='MONTHLY' AND c.status='PENDING' AND c.provider_payment_id IS NULL
        ORDER BY c.due_date, c.id LIMIT 15 FOR UPDATE OF c SKIP LOCKED
        """,nativeQuery=true)
    List<FinancialCharge> lockPendingAdvanceCharges();
    Optional<FinancialCharge> findFirstByVehicle_IdAndChargeTypeOrderByCreatedAtAsc(UUID vehicleId, FinancialChargeType type);

    /** Recuperação de webhooks perdidos: só títulos existentes e associados à carteira Asaas. */
    @org.springframework.data.jpa.repository.Query(value="""
        SELECT c.id FROM financial_charges c
        JOIN commercial_vehicles v ON v.id=c.vehicle_id
        WHERE c.charge_type='ADHESION' AND c.provider_payment_id IS NOT NULL
          AND (c.status IN ('PENDING','OVERDUE','CONFIRMED','ERROR')
               OR (c.status='RECEIVED' AND v.status='PENDING_PAYMENT'))
        ORDER BY c.updated_at ASC, c.id
        LIMIT 30
        """, nativeQuery=true)
    List<UUID> findAdhesionChargesNeedingReconciliation();
}
