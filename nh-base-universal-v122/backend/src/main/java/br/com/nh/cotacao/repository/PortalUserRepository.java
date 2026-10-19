package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortalUserRepository extends JpaRepository<PortalUser, UUID> {
    Optional<PortalUser> findByNormalizedUsername(String normalizedUsername);
    Optional<PortalUser> findByConsultantId(UUID consultantId);
    Optional<PortalUser> findByAssociateId(UUID associateId);
    List<PortalUser> findAllByOrderByRoleAscUsernameAsc();
    long countByRoleAndActiveTrue(PortalRole role);
}
