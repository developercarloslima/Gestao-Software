package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.Plan;
import br.com.nh.cotacao.entity.PriceRange;
import br.com.nh.cotacao.entity.VehicleCategory;
import br.com.nh.cotacao.repository.PlanRepository;
import br.com.nh.cotacao.repository.PriceRangeRepository;
import br.com.nh.cotacao.repository.PromotionalMotorcyclePriceRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingServiceTest {

    @Test
    void robberyAndTheftNationalCarPlanIsAlwaysTenPercentCheaperThanEconomicPlan() {
        PriceRangeRepository priceRangeRepository = mock(PriceRangeRepository.class);
        PromotionalMotorcyclePriceRepository promotionalRepository = mock(PromotionalMotorcyclePriceRepository.class);
        PlanRepository planRepository = mock(PlanRepository.class);
        PricingService service = new PricingService(priceRangeRepository, promotionalRepository, planRepository);

        VehicleCategory category = mock(VehicleCategory.class);
        when(category.getCode()).thenReturn("CAR_NATIONAL");

        Plan economic = mock(Plan.class);
        when(economic.getId()).thenReturn(1L);
        when(economic.getCode()).thenReturn("CAR_ECONOMICO");
        when(economic.getCategory()).thenReturn(category);
        when(economic.getTrackerRequiredAbove()).thenReturn(null);

        Plan robberyTheft = mock(Plan.class);
        when(robberyTheft.getId()).thenReturn(15L);
        when(robberyTheft.getName()).thenReturn("Plano Roubo e Furto Carros Nacionais");
        when(robberyTheft.getCategory()).thenReturn(category);
        when(robberyTheft.getTrackerRequiredAbove()).thenReturn(new BigDecimal("1.00"));
        when(robberyTheft.getTrackerMonthlyFee()).thenReturn(new BigDecimal("20.00"));
        when(robberyTheft.getTrackerInstallationFee()).thenReturn(new BigDecimal("200.00"));

        BigDecimal fipe = new BigDecimal("38990.00");
        PriceRange economicRange = PriceRange.create(
                economic,
                new BigDecimal("35000.01"),
                new BigDecimal("40000.00"),
                new BigDecimal("100.00")
        );

        when(planRepository.findAvailableByCode("CAR_ECONOMICO")).thenReturn(Optional.of(economic));
        when(priceRangeRepository.findFirstByPlanIdAndMinValueLessThanEqualAndMaxValueGreaterThanEqualOrderByMinValueAsc(
                1L, fipe, fipe
        )).thenReturn(Optional.of(economicRange));

        PricingService.PricingResult result = service.calculateBreakdown(robberyTheft, fipe).orElseThrow();

        assertEquals(new BigDecimal("90.00"), result.totalMonthlyValue());
        assertEquals(new BigDecimal("70.00"), result.tableMonthlyValue());
        assertEquals(new BigDecimal("20.00"), result.mandatoryMonthlyFee());
        assertEquals(new BigDecimal("200.00"), result.oneTimeFee());

        verify(priceRangeRepository, never())
                .findFirstByPlanIdAndMinValueLessThanEqualAndMaxValueGreaterThanEqualOrderByMinValueAsc(15L, fipe, fipe);
    }
}
