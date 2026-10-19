package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.Plan;
import br.com.nh.cotacao.entity.Region;
import br.com.nh.cotacao.entity.VehicleCategory;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.CoverageRepository;
import br.com.nh.cotacao.repository.CoverageRuleRepository;
import br.com.nh.cotacao.repository.DiscountBenefitRuleRepository;
import br.com.nh.cotacao.repository.DiscountRuleRepository;
import br.com.nh.cotacao.repository.PlanCoverageRepository;
import br.com.nh.cotacao.repository.PlanRepository;
import br.com.nh.cotacao.repository.PriceRangeRepository;
import br.com.nh.cotacao.repository.PromotionalMotorcyclePriceRepository;
import br.com.nh.cotacao.repository.VehicleCategoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminCatalogServiceTest {

    @Test
    void deletePlanRemovesCatalogChildrenBeforePlanAndPreservesSharedCoverage() {
        PriceRangeRepository priceRepository = mock(PriceRangeRepository.class);
        PlanCoverageRepository planCoverageRepository = mock(PlanCoverageRepository.class);
        PlanRepository planRepository = mock(PlanRepository.class);
        CoverageRepository coverageRepository = mock(CoverageRepository.class);
        CoverageRuleRepository coverageRuleRepository = mock(CoverageRuleRepository.class);
        VehicleCategoryRepository categoryRepository = mock(VehicleCategoryRepository.class);
        CatalogChangeAuditRepository auditRepository = mock(CatalogChangeAuditRepository.class);
        PromotionalMotorcyclePriceRepository promotionalRepository = mock(PromotionalMotorcyclePriceRepository.class);
        DiscountRuleRepository discountRuleRepository = mock(DiscountRuleRepository.class);
        DiscountBenefitRuleRepository discountBenefitRuleRepository = mock(DiscountBenefitRuleRepository.class);

        AdminCatalogService service = new AdminCatalogService(
                priceRepository,
                planCoverageRepository,
                planRepository,
                coverageRepository,
                coverageRuleRepository,
                categoryRepository,
                auditRepository,
                promotionalRepository,
                discountRuleRepository,
                discountBenefitRuleRepository
        );

        long planId = 15L;
        Plan plan = mock(Plan.class);
        VehicleCategory category = mock(VehicleCategory.class);
        when(plan.getName()).thenReturn("Plano teste");
        when(plan.getCategory()).thenReturn(category);
        when(category.getName()).thenReturn("Carros");
        when(plan.getRegion()).thenReturn(Region.NATIONAL);
        when(plan.getDisplayOrder()).thenReturn(1);
        when(plan.getActive()).thenReturn(true);
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        when(priceRepository.countByPlan_Id(planId)).thenReturn(3L);
        when(planCoverageRepository.countByPlan_Id(planId)).thenReturn(2L);
        when(planCoverageRepository.findCoverageIdsByPlanId(planId)).thenReturn(List.of(100L, 200L));
        when(planCoverageRepository.countByCoverage_Id(100L)).thenReturn(0L);
        when(planCoverageRepository.countByCoverage_Id(200L)).thenReturn(1L);

        service.deletePlan(planId, "admin");

        var order = inOrder(priceRepository, planCoverageRepository, planRepository);
        order.verify(priceRepository).deleteAllByPlanId(planId);
        order.verify(planCoverageRepository).deleteAllByPlanId(planId);
        order.verify(planRepository).deleteById(planId);
        order.verify(planRepository).flush();

        verify(coverageRuleRepository).deleteByCoverage_Id(100L);
        verify(coverageRepository).deleteById(100L);
        verify(coverageRuleRepository, never()).deleteByCoverage_Id(200L);
        verify(coverageRepository, never()).deleteById(200L);
        verify(auditRepository).save(any());
    }
}
