package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.InspectionAsset;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InspectionAssetRepository extends JpaRepository<InspectionAsset, UUID> {
    @EntityGraph(attributePaths = {"inspectionRequest", "inspectionRequest.consultant"})
    Optional<InspectionAsset> findByIdAndInspectionRequest_Id(UUID id, UUID inspectionId);

    List<InspectionAsset> findAllByInspectionRequest_IdOrderBySortOrderAsc(UUID inspectionId);
}
