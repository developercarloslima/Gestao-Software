package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultantWithdrawalRepository extends JpaRepository<ConsultantWithdrawal, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from ConsultantWithdrawal w where w.id = :id")
    Optional<ConsultantWithdrawal> findByIdForUpdate(@Param("id") UUID id);
    Optional<ConsultantWithdrawal> findByProviderTransferId(String providerTransferId);
    List<ConsultantWithdrawal> findAllByConsultant_IdOrderByCreatedAtDesc(UUID consultantId);
    @Query(value="select coalesce(sum(net_amount),0) from consultant_withdrawals where consultant_id=:consultantId and status='PAID'", nativeQuery=true)
    BigDecimal sumPaid(@Param("consultantId") UUID consultantId);
    @Query(value="select coalesce(sum(amount),0) from consultant_withdrawals where consultant_id=:consultantId and status in ('REQUESTED','PROCESSING','PAID')", nativeQuery=true)
    BigDecimal sumCommitted(@Param("consultantId") UUID consultantId);
    List<ConsultantWithdrawal> findAllByOrderByCreatedAtDesc();
}
