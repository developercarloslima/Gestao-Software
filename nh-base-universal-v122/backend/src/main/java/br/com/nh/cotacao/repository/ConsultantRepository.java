package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.entity.CollaboratorRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultantRepository extends JpaRepository<Consultant, UUID> {
    List<Consultant> findByActiveTrueOrderByNameAsc();
    List<Consultant> findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole role);
    List<Consultant> findAllByOrderByNameAsc();
    Optional<Consultant> findByNormalizedName(String normalizedName);
    Optional<Consultant> findFirstByActiveTrueAndRoleAndLastPortalLoginAtIsNotNullOrderByLastPortalLoginAtDesc(CollaboratorRole role);
    long countByAssignedAnalyst_IdAndActiveTrue(UUID analystId);
}
