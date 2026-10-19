package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.DiscountBenefitRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiscountBenefitRuleRepository extends JpaRepository<DiscountBenefitRule, Long> {
    List<DiscountBenefitRule> findByCoverage_Id(Long coverageId);
    void deleteByCoverage_Id(Long coverageId);
}
