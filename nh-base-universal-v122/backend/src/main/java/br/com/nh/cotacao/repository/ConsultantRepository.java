package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.entity.CollaboratorRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultantRepository extends JpaRepository<Consultant, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select c from Consultant c where c.id = :id")
    Optional<Consultant> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    List<Consultant> findByActiveTrueOrderByNameAsc();
    List<Consultant> findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole role);
    List<Consultant> findAllByOrderByNameAsc();
    Optional<Consultant> findByNormalizedName(String normalizedName);
    Optional<Consultant> findFirstByActiveTrueAndRoleAndLastPortalLoginAtIsNotNullOrderByLastPortalLoginAtDesc(CollaboratorRole role);
    long countByAssignedAnalyst_IdAndActiveTrue(UUID analystId);
}
