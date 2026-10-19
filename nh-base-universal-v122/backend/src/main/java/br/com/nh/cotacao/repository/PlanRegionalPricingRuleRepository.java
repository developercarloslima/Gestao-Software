package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.PlanRegionalPricingRule;
import br.com.nh.cotacao.entity.PricingRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlanRegionalPricingRuleRepository extends JpaRepository<PlanRegionalPricingRule, Long> {
    Optional<PlanRegionalPricingRule> findByPlan_IdAndPricingRegion(Long planId, PricingRegion pricingRegion);
}
