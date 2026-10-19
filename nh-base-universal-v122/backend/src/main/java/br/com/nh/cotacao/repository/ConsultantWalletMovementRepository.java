package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultantWalletMovementRepository extends JpaRepository<ConsultantWalletMovement, UUID> {
    List<ConsultantWalletMovement> findAllByConsultant_IdAndLockedTrue(UUID consultantId);
    boolean existsByCharge_IdAndMovementType(UUID chargeId, WalletMovementType movementType);
    Optional<ConsultantWalletMovement> findFirstByCharge_IdAndMovementType(UUID chargeId, WalletMovementType movementType);
    boolean existsByWithdrawal_IdAndMovementType(UUID withdrawalId, WalletMovementType movementType);
    @Query("select coalesce(sum(m.amount),0) from ConsultantWalletMovement m where m.consultant.id=:consultantId and m.locked=:locked")
    BigDecimal sumByLock(@Param("consultantId") UUID consultantId, @Param("locked") boolean locked);
}
