package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.DiscountRule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscountRuleRepository extends JpaRepository<DiscountRule, Long> {
    @EntityGraph(attributePaths = {"plans", "benefitRules", "benefitRules.coverage"})
    List<DiscountRule> findAllByOrderByDisplayOrderAscPercentAsc();

    @EntityGraph(attributePaths = {"plans", "benefitRules", "benefitRules.coverage"})
    Optional<DiscountRule> findByPercent(Integer percent);

    boolean existsByPercent(Integer percent);
    boolean existsByPercentAndIdNot(Integer percent, Long id);
}
