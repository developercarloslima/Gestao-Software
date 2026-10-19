package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.CatalogChangeAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogChangeAuditRepository extends JpaRepository<CatalogChangeAudit, Long> {
    List<CatalogChangeAudit> findAllByOrderByChangedAtDesc();
}
