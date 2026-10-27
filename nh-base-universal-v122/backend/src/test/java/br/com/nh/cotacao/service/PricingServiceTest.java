package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.Plan;
import br.com.nh.cotacao.entity.PriceRange;
import br.com.nh.cotacao.entity.PricingRegion;
import br.com.nh.cotacao.entity.VehicleCategory;
import br.com.nh.cotacao.repository.PlanRepository;
import br.com.nh.cotacao.repository.PlanRegionalPricingRuleRepository;
import br.com.nh.cotacao.repository.PriceRangeRepository;
import br.com.nh.cotacao.repository.PromotionalMotorcyclePriceRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingServiceTest {

    @Test
    void shouldCalculateRegionalPriceAndMandatoryTrackerForNationalCar() {
        PriceRangeRepository priceRangeRepository = mock(PriceRangeRepository.class);
        PromotionalMotorcyclePriceRepository promotionalRepository = mock(PromotionalMotorcyclePriceRepository.class);
        PlanRepository planRepository = mock(PlanRepository.class);
        PlanRegionalPricingRuleRepository regionalRuleRepository = mock(PlanRegionalPricingRuleRepository.class);
        PricingService service = new PricingService(priceRangeRepository, promotionalRepository, planRepository, regionalRuleRepository);

        VehicleCategory category = mock(VehicleCategory.class);
        when(category.getCode()).thenReturn("CAR_NATIONAL");

        Plan plan = mock(Plan.class);
        when(plan.getId()).thenReturn(15L);
        when(plan.getCategory()).thenReturn(category);
        when(plan.getTrackerRequiredAbove()).thenReturn(new BigDecimal("1.00"));
        when(plan.getTrackerMonthlyFee()).thenReturn(new BigDecimal("20.00"));
        when(plan.getTrackerInstallationFee()).thenReturn(new BigDecimal("200.00"));

        BigDecimal fipe = new BigDecimal("38990.00");
        PriceRange selectedRange = PriceRange.create(
                plan,
                new BigDecimal("35000.01"),
                new BigDecimal("40000.00"),
                new BigDecimal("100.00")
        );

        when(priceRangeRepository.findFirstByPlanIdAndPricingRegionAndMinValueLessThanEqualAndMaxValueGreaterThanEqualOrderByMinValueAsc(
                15L, PricingRegion.OUTSIDE_NORTHEAST, fipe, fipe
        )).thenReturn(Optional.of(selectedRange));

        PricingService.PricingResult result = service.calculateBreakdown(plan, fipe, PricingRegion.OUTSIDE_NORTHEAST).orElseThrow();

        assertEquals(new BigDecimal("120.00"), result.totalMonthlyValue());
        assertEquals(new BigDecimal("100.00"), result.tableMonthlyValue());
        assertEquals(new BigDecimal("20.00"), result.mandatoryMonthlyFee());
        assertEquals(new BigDecimal("200.00"), result.oneTimeFee());

        verify(priceRangeRepository, never())
                .findFirstByPlanIdAndPricingRegionAndMinValueLessThanEqualAndMaxValueGreaterThanEqualOrderByMinValueAsc(15L, PricingRegion.NORTHEAST, fipe, fipe);
    }
}
