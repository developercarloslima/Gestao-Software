package br.com.nh.cotacao.controller;

import br.com.nh.cotacao.dto.AdminDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.service.AdminCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/catalog")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogController {
    private final AdminCatalogService service;

    public AdminCatalogController(AdminCatalogService service) { this.service = service; }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() { return service.categories(); }

    @PatchMapping("/categories/{id}")
    public CategoryResponse updateCategoryStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCategoryStatusRequest request,
            Authentication auth
    ) { return service.updateCategoryStatus(id, request, username(auth)); }

    @GetMapping("/prices")
    public List<PriceRangeResponse> prices() { return service.priceRanges(); }

    @PostMapping("/prices")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceRangeResponse createPrice(
            @Valid @RequestBody CreatePriceRangeRequest request,
            Authentication auth
    ) { return service.createPriceRange(request, username(auth)); }

    @PatchMapping("/prices/{id}")
    public PriceRangeResponse updatePrice(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePriceRangeRequest request,
            Authentication auth
    ) { return service.updatePriceRange(id, request, username(auth)); }

    @DeleteMapping("/prices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePrice(@PathVariable Long id, Authentication auth) {
        service.deletePriceRange(id, username(auth));
    }

    @GetMapping("/promotional-motorcycle-prices")
    public List<PromotionalMotorcyclePriceResponse> promotionalMotorcyclePrices() {
        return service.promotionalMotorcyclePrices();
    }

    @PatchMapping("/promotional-motorcycle-prices/{id}")
    public PromotionalMotorcyclePriceResponse updatePromotionalMotorcyclePrice(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePromotionalMotorcyclePriceRequest request,
            Authentication auth
    ) { return service.updatePromotionalMotorcyclePrice(id, request, username(auth)); }

    @DeleteMapping("/promotional-motorcycle-prices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePromotionalMotorcyclePrice(@PathVariable Long id, Authentication auth) {
        service.deletePromotionalMotorcyclePrice(id, username(auth));
    }

    @GetMapping("/plans")
    public List<PlanAdminResponse> plans() { return service.plans(); }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanAdminResponse createPlan(
            @Valid @RequestBody CreatePlanRequest request,
            Authentication auth
    ) { return service.createPlan(request, username(auth)); }

    @PatchMapping("/plans/{id}")
    public PlanAdminResponse updatePlan(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePlanRequest request,
            Authentication auth
    ) { return service.updatePlan(id, request, username(auth)); }

    @DeleteMapping("/plans/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlan(@PathVariable Long id, Authentication auth) {
        service.deletePlan(id, username(auth));
    }

    @GetMapping("/benefits")
    public List<BenefitAdminResponse> benefits() { return service.benefits(); }

    @PostMapping("/benefits")
    @ResponseStatus(HttpStatus.CREATED)
    public BenefitAdminResponse createBenefit(
            @Valid @RequestBody CreateBenefitRequest request,
            Authentication auth
    ) { return service.createBenefit(request, username(auth)); }

    @PatchMapping("/benefits/{id}")
    public BenefitAdminResponse updateBenefit(
            @PathVariable Long id,
            @Valid @RequestBody UpdateBenefitRequest request,
            Authentication auth
    ) { return service.updateBenefit(id, request, username(auth)); }

    @DeleteMapping("/benefits/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBenefit(@PathVariable Long id, Authentication auth) {
        service.deleteBenefit(id, username(auth));
    }

    @GetMapping("/coverages")
    public List<CoverageAdminResponse> coverages() { return service.coverages(); }

    @PostMapping("/coverages")
    @ResponseStatus(HttpStatus.CREATED)
    public List<CoverageAdminResponse> createCoverage(
            @Valid @RequestBody CreateCoverageRequest request,
            Authentication auth
    ) { return service.createCoverage(request, username(auth)); }

    @PatchMapping("/coverages/{id}")
    public List<CoverageAdminResponse> updateCoverage(
            @PathVariable Long id,
            @Valid @RequestBody UpdateCoverageRequest request,
            Authentication auth
    ) { return service.updateCoverage(id, request, username(auth)); }

    @DeleteMapping("/coverages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCoverage(@PathVariable Long id, Authentication auth) {
        service.deleteCoverage(id, username(auth));
    }

    @DeleteMapping("/coverage-groups/{coverageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCoverageGroup(@PathVariable Long coverageId, Authentication auth) {
        service.deleteCoverageGroup(coverageId, username(auth));
    }

    @GetMapping("/optionals")
    public List<OptionalPriceResponse> optionals() { return service.optionals(); }

    @PatchMapping("/optionals/{id}")
    public OptionalPriceResponse updateOptional(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePriceRequest request,
            Authentication auth
    ) { return service.updateOptional(id, request.monthlyPrice(), username(auth)); }

    @GetMapping("/audit")
    public List<AuditResponse> audit() { return service.audit(); }

    private String username(Authentication auth) {
        return ((PortalPrincipal) auth.getPrincipal()).username();
    }
}
