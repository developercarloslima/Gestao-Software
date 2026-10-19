package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.CommercialVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommercialVehicleRepository extends JpaRepository<CommercialVehicle, UUID> {
    Optional<CommercialVehicle> findByQuotation_Id(UUID quotationId);
    Optional<CommercialVehicle> findByAsaasSubscriptionId(String asaasSubscriptionId);
    List<CommercialVehicle> findAllByAssociate_IdOrderByCreatedAtDesc(UUID associateId);
    List<CommercialVehicle> findAllByConsultant_IdOrderByCreatedAtDesc(UUID consultantId);
    List<CommercialVehicle> findAllByOrderByCreatedAtDesc();
}
