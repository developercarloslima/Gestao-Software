package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.Associate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssociateRepository extends JpaRepository<Associate, UUID> {
    Optional<Associate> findByDocument(String document);
    Optional<Associate> findByAsaasCustomerId(String asaasCustomerId);
    @Query("select a from Associate a where a.active=true and (:q = '' or a.normalizedName like concat('%',:q,'%') or (:digits <> '' and coalesce(a.document,'') like concat('%',:digits,'%'))) order by a.name")
    List<Associate> search(@Param("q") String q, @Param("digits") String digits);
}
