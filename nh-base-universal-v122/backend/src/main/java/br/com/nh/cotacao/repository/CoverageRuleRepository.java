package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.CoverageRule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CoverageRuleRepository extends JpaRepository<CoverageRule, Long> {
    @EntityGraph(attributePaths = {"coverage"})
    List<CoverageRule> findByCoverage_IdOrderBySortOrderAscIdAsc(Long coverageId);
    void deleteByCoverage_Id(Long coverageId);
}
