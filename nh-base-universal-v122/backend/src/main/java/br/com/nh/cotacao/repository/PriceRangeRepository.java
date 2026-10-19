package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.PriceRange;
import br.com.nh.cotacao.entity.PricingRegion;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PriceRangeRepository extends JpaRepository<PriceRange, Long> {
    Optional<PriceRange> findFirstByPlanIdAndPricingRegionAndMinValueLessThanEqualAndMaxValueGreaterThanEqualOrderByMinValueAsc(
            Long planId,
            PricingRegion pricingRegion,
            BigDecimal fipeForMin,
            BigDecimal fipeForMax
    );

    boolean existsByPlan_IdAndMinValueLessThanEqualAndMaxValueGreaterThanEqual(
            Long planId,
            BigDecimal maxValue,
            BigDecimal minValue
    );

    List<PriceRange> findByPlan_Id(Long planId);
    List<PriceRange> findByPlan_IdAndPricingRegion(Long planId, PricingRegion pricingRegion);
    long countByPlan_Id(Long planId);

    @Modifying(flushAutomatically = true)
    @Query("delete from PriceRange p where p.plan.id = :planId")
    int deleteAllByPlanId(@Param("planId") Long planId);

    @EntityGraph(attributePaths = {"plan", "plan.category"})
    @Query("select p from PriceRange p order by p.pricingRegion asc, p.plan.name asc, p.minValue asc")
    List<PriceRange> findAllForAdmin();
}
