package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.Coverage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CoverageRepository extends JpaRepository<Coverage, Long> {
    Optional<Coverage> findByCode(String code);
    boolean existsByCode(String code);
    boolean existsByCodeAndIdNot(String code, Long id);
}
