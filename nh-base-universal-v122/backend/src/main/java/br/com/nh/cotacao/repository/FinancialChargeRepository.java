package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinancialChargeRepository extends JpaRepository<FinancialCharge, UUID> {
    Optional<FinancialCharge> findByProviderPaymentId(String providerPaymentId);
    List<FinancialCharge> findAllByVehicle_IdOrderByDueDateDescCreatedAtDesc(UUID vehicleId);
    long countByVehicle_IdAndChargeType(UUID vehicleId, FinancialChargeType type);
    long countByVehicle_IdAndChargeTypeAndStatus(UUID vehicleId, FinancialChargeType type, FinancialChargeStatus status);
    long countByConsultant_IdAndChargeTypeAndStatus(UUID consultantId, FinancialChargeType type, FinancialChargeStatus status);
    boolean existsByVehicle_IdAndChargeType(UUID vehicleId, FinancialChargeType type);
    Optional<FinancialCharge> findFirstByVehicle_IdAndChargeTypeOrderByCreatedAtAsc(UUID vehicleId, FinancialChargeType type);
}
