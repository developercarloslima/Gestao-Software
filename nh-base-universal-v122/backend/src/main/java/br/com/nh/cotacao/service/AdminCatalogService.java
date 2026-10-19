package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.AdminDtos.*;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

@Service
public class AdminCatalogService {
    private final PriceRangeRepository priceRepository;
    private final PlanCoverageRepository planCoverageRepository;
    private final PlanRepository planRepository;
    private final CoverageRepository coverageRepository;
    private final CoverageRuleRepository coverageRuleRepository;
    private final VehicleCategoryRepository categoryRepository;
    private final CatalogChangeAuditRepository auditRepository;
    private final PromotionalMotorcyclePriceRepository promotionalMotorcyclePriceRepository;
    private final DiscountRuleRepository discountRuleRepository;
    private final DiscountBenefitRuleRepository discountBenefitRuleRepository;

    public AdminCatalogService(
            PriceRangeRepository priceRepository,
            PlanCoverageRepository planCoverageRepository,
            PlanRepository planRepository,
            CoverageRepository coverageRepository,
            CoverageRuleRepository coverageRuleRepository,
            VehicleCategoryRepository categoryRepository,
            CatalogChangeAuditRepository auditRepository,
            PromotionalMotorcyclePriceRepository promotionalMotorcyclePriceRepository,
            DiscountRuleRepository discountRuleRepository,
            DiscountBenefitRuleRepository discountBenefitRuleRepository
    ) {
        this.priceRepository = priceRepository;
        this.planCoverageRepository = planCoverageRepository;
        this.planRepository = planRepository;
        this.coverageRepository = coverageRepository;
        this.coverageRuleRepository = coverageRuleRepository;
        this.categoryRepository = categoryRepository;
        this.auditRepository = auditRepository;
        this.promotionalMotorcyclePriceRepository = promotionalMotorcyclePriceRepository;
        this.discountRuleRepository = discountRuleRepository;
        this.discountBenefitRuleRepository = discountBenefitRuleRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categories() {
        return categoryRepository.findAllByOrderByNameAsc().stream()
                .map(this::toCategoryResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse updateCategoryStatus(Long id, UpdateCategoryStatusRequest request, String username) {
        VehicleCategory category = findCategory(id);
        boolean oldActive = Boolean.TRUE.equals(category.getActive());
        boolean newActive = Boolean.TRUE.equals(request.active());
        if (oldActive == newActive) return toCategoryResponse(category);

        category.updateActive(newActive);
        categoryRepository.save(category);
        auditRepository.save(CatalogChangeAudit.createText(
                "VEHICLE_CATEGORY", category.getId(), category.getName() + (newActive ? " ativada" : " desativada"),
                "ativo=" + oldActive, "ativo=" + newActive, username
        ));
        return toCategoryResponse(category);
    }

    @Transactional(readOnly = true)
    public List<PriceRangeResponse> priceRanges() {
        return priceRepository.findAllForAdmin().stream().map(this::toPriceResponse).toList();
    }

    @Transactional
    public PriceRangeResponse createPriceRange(CreatePriceRangeRequest request, String username) {
        Plan plan = findPlan(request.planId());
        validateRange(plan.getId(), request.pricingRegion(), null, request.minValue(), request.maxValue());
        PriceRange range = priceRepository.save(PriceRange.create(
                plan, request.pricingRegion(), request.minValue(), request.maxValue(), request.monthlyPrice()
        ));
        auditRepository.save(CatalogChangeAudit.createText(
                "PRICE_RANGE", range.getId(), "Nova faixa — " + plan.getName(),
                null, priceSummary(range), username
        ));
        return toPriceResponse(range);
    }

    @Transactional
    public PriceRangeResponse updatePriceRange(Long id, UpdatePriceRangeRequest request, String username) {
        PriceRange range = priceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Faixa de preço não encontrada."));
        String old = priceSummary(range);
        validateRange(range.getPlan().getId(), request.pricingRegion(), id, request.minValue(), request.maxValue());
        range.update(request.pricingRegion(), request.minValue(), request.maxValue(), request.monthlyPrice());
        priceRepository.save(range);
        auditRepository.save(CatalogChangeAudit.createText(
                "PRICE_RANGE", id, "Faixa alterada — " + range.getPlan().getName(),
                old, priceSummary(range), username
        ));
        return toPriceResponse(range);
    }

    @Transactional
    public void deletePriceRange(Long id, String username) {
        PriceRange range = priceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Faixa de preço não encontrada."));
        String old = priceSummary(range);
        String description = "Faixa excluída — " + range.getPlan().getName();
        priceRepository.delete(range);
        auditRepository.save(CatalogChangeAudit.createText(
                "PRICE_RANGE", id, description, old, null, username
        ));
    }

    @Transactional(readOnly = true)
    public List<PromotionalMotorcyclePriceResponse> promotionalMotorcyclePrices() {
        return promotionalMotorcyclePriceRepository.findAllByOrderBySortOrderAsc().stream()
                .map(this::toPromotionalMotorcyclePriceResponse)
                .toList();
    }

    @Transactional
    public PromotionalMotorcyclePriceResponse updatePromotionalMotorcyclePrice(
            Long id, UpdatePromotionalMotorcyclePriceRequest request, String username
    ) {
        PromotionalMotorcyclePrice item = promotionalMotorcyclePriceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Faixa promocional de motocicleta não encontrada."));
        String old = promotionalMotorcyclePriceSummary(item);
        item.updateConfiguration(
                request.label(), request.minFipe(), request.maxFipe(),
                request.minCc(), request.maxCc(), request.monthlyPrice()
        );
        promotionalMotorcyclePriceRepository.save(item);
        auditRepository.save(CatalogChangeAudit.createText(
                "PROMO_MOTORCYCLE_PRICE", item.getId(),
                "Faixa promocional alterada — " + item.getLabel(),
                old, promotionalMotorcyclePriceSummary(item), username
        ));
        return toPromotionalMotorcyclePriceResponse(item);
    }

    @Transactional
    public void deletePromotionalMotorcyclePrice(Long id, String username) {
        PromotionalMotorcyclePrice item = promotionalMotorcyclePriceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Faixa promocional de motocicleta não encontrada."));
        String old = promotionalMotorcyclePriceSummary(item);
        String description = "Faixa promocional excluída — " + item.getLabel();
        promotionalMotorcyclePriceRepository.delete(item);
        auditRepository.save(CatalogChangeAudit.createText(
                "PROMO_MOTORCYCLE_PRICE", id, description, old, null, username
        ));
    }

    @Transactional(readOnly = true)
    public List<PlanAdminResponse> plans() {
        return planRepository.findAllForAdmin().stream().map(this::toPlanResponse).toList();
    }

    @Transactional
    public PlanAdminResponse createPlan(CreatePlanRequest request, String username) {
        VehicleCategory category = findCategory(request.categoryId());
        String code = uniquePlanCode(category.getCode(), request.motorcycleOrigin(), request.name());
        Plan plan = planRepository.save(Plan.create(
                code, request.name(), request.subtitle(), category, Region.NATIONAL, request.motorcycleOrigin(), request.displayOrder(), request.active(),
                request.extraAbove(), request.extraStep(), request.extraIncrement(), request.extraBasePrice(),
                request.trackerRequiredAbove(), request.trackerInstallationFee(), request.trackerMonthlyFee()
        ));
        if (request.benefits() != null) {
            syncPlanBenefits(plan, request.benefits());
        }
        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN", plan.getId(), "Plano criado — " + plan.getName(), null, planSummary(plan), username
        ));
        return toPlanResponse(plan);
    }

    @Transactional
    public PlanAdminResponse updatePlan(Long id, UpdatePlanRequest request, String username) {
        Plan plan = findPlan(id);
        VehicleCategory category = findCategory(request.categoryId());
        String old = planSummary(plan);
        plan.updateAdmin(
                plan.getCode(), request.name(), request.subtitle(), category, Region.NATIONAL, request.motorcycleOrigin(), request.displayOrder(), request.active(),
                request.extraAbove(), request.extraStep(), request.extraIncrement(), request.extraBasePrice(),
                request.trackerRequiredAbove(), request.trackerInstallationFee(), request.trackerMonthlyFee()
        );
        planRepository.save(plan);
        if (request.benefits() != null) {
            syncPlanBenefits(plan, request.benefits());
        }
        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN", plan.getId(), "Plano alterado — " + plan.getName(), old, planSummary(plan), username
        ));
        return toPlanResponse(plan);
    }

    @Transactional
    public void deletePlan(Long id, String username) {
        Plan plan = findPlan(id);

        // O histórico das cotações guarda snapshots do código/nome/valor do plano e
        // não possui FK para plans. Portanto, excluir o catálogo não apaga vendas
        // nem cotações antigas.
        long priceCount = priceRepository.countByPlan_Id(id);
        long coverageCount = planCoverageRepository.countByPlan_Id(id);
        String old = planSummary(plan)
                + "; faixas=" + priceCount
                + "; coberturas=" + coverageCount;
        String name = plan.getName();

        // Removemos explicitamente os filhos antes do plano. Apesar das FKs terem
        // ON DELETE CASCADE, fazer o delete em lote evita conflito entre o cascade
        // do PostgreSQL e entidades PlanCoverage/PriceRange gerenciadas pelo Hibernate.
        priceRepository.deleteAllByPlanId(id);
        planCoverageRepository.deleteAllByPlanId(id);
        planRepository.deleteById(id);
        planRepository.flush();

        // O catálogo de benefícios é independente dos planos. Ao excluir um plano,
        // os benefícios permanecem disponíveis para reutilização em outros planos.

        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN", id, "Plano excluído — " + name, old, null, username
        ));
    }

    @Transactional(readOnly = true)
    public List<BenefitAdminResponse> benefits() {
        Map<Long, List<PlanCoverage>> assignmentsByCoverage = new LinkedHashMap<>();
        for (PlanCoverage assignment : planCoverageRepository.findAllForAdmin()) {
            assignmentsByCoverage.computeIfAbsent(assignment.getCoverage().getId(), ignored -> new java.util.ArrayList<>()).add(assignment);
        }
        return coverageRepository.findAll().stream()
                .sorted(Comparator.comparing(Coverage::getName, String.CASE_INSENSITIVE_ORDER))
                .map(coverage -> toBenefitResponse(coverage, assignmentsByCoverage.getOrDefault(coverage.getId(), List.of())))
                .toList();
    }

    @Transactional
    public BenefitAdminResponse createBenefit(CreateBenefitRequest request, String username) {
        assertCoverageNameAvailable(request.name(), null);
        Coverage coverage = Coverage.create(uniqueCoverageCode("BENEFIT", request.name()), request.name());
        applyOptionalVehicleCategories(coverage, request.optionalVehicleCategoryIds());
        coverage = coverageRepository.save(coverage);
        saveCoverageRules(coverage, request.rules());
        syncBenefitAssignments(coverage, request.assignments());
        syncBenefitDiscountEffects(coverage, request.discountEffects());
        List<PlanCoverage> assignments = assignmentsForCoverage(coverage.getId());
        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN_COVERAGE", coverage.getId(), "Benefício criado — " + coverage.getName(),
                null, coverageGroupSummary(coverage, assignments), username
        ));
        return toBenefitResponse(coverage, assignments);
    }

    @Transactional
    public BenefitAdminResponse updateBenefit(Long coverageId, UpdateBenefitRequest request, String username) {
        Coverage coverage = coverageRepository.findById(coverageId)
                .orElseThrow(() -> new IllegalArgumentException("Benefício não encontrado."));
        List<PlanCoverage> previousAssignments = assignmentsForCoverage(coverageId);
        String old = coverageGroupSummary(coverage, previousAssignments);
        String requestedName = request.name().trim();
        assertCoverageNameAvailable(requestedName, coverageId);
        if (!coverage.getName().equals(requestedName)) {
            coverage.updateAdmin(coverage.getCode(), requestedName);
        }
        if (request.optionalVehicleCategoryIds() != null) {
            applyOptionalVehicleCategories(coverage, request.optionalVehicleCategoryIds());
        }
        coverageRepository.save(coverage);
        if (request.rules() != null) {
            saveCoverageRules(coverage, request.rules());
        }
        if (request.assignments() != null) {
            syncBenefitAssignments(coverage, request.assignments());
        }
        if (request.discountEffects() != null) {
            syncBenefitDiscountEffects(coverage, request.discountEffects());
        }
        List<PlanCoverage> assignments = assignmentsForCoverage(coverageId);
        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN_COVERAGE", coverage.getId(), "Benefício alterado — " + coverage.getName(),
                old, coverageGroupSummary(coverage, assignments), username
        ));
        return toBenefitResponse(coverage, assignments);
    }

    @Transactional
    public void deleteBenefit(Long coverageId, String username) {
        deleteCoverageGroup(coverageId, username);
    }

    @Transactional(readOnly = true)
    public List<CoverageAdminResponse> coverages() {
        return planCoverageRepository.findAllForAdmin().stream().map(this::toCoverageResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<OptionalPriceResponse> optionals() {
        return planCoverageRepository.findForAdmin(CoverageStatus.OPTIONAL).stream().map(item -> new OptionalPriceResponse(
                item.getId(), item.getPlan().getId(), item.getPlan().getName(), item.getCoverage().getName(),
                item.getDetail(), item.getMonthlyPrice()
        )).toList();
    }

    @Transactional
    public OptionalPriceResponse updateOptional(Long id, BigDecimal value, String username) {
        PlanCoverage item = planCoverageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Opcional não encontrado."));
        BigDecimal old = item.getMonthlyPrice();
        item.updateMonthlyPrice(value);
        planCoverageRepository.save(item);
        auditRepository.save(CatalogChangeAudit.create(
                "OPTIONAL", id, item.getPlan().getName() + " — " + item.getCoverage().getName(), old, value, username
        ));
        return new OptionalPriceResponse(
                item.getId(), item.getPlan().getId(), item.getPlan().getName(), item.getCoverage().getName(),
                item.getDetail(), item.getMonthlyPrice()
        );
    }

    @Transactional
    public List<CoverageAdminResponse> createCoverage(CreateCoverageRequest request, String username) {
        List<Plan> targetPlans = request.planIds().stream().distinct().map(this::findPlan).toList();
        if (targetPlans.isEmpty()) throw new IllegalArgumentException("Selecione pelo menos um plano.");

        if (isCanonicalThirdPartyName(request.coverageName())) {
            throw new IllegalArgumentException(
                    "A cobertura de danos ao veículo de terceiros já existe. Edite a cobertura atual e suas regras FIPE em vez de criar outra."
            );
        }
        assertCoverageNameAvailable(request.coverageName(), null);
        String code = uniqueCoverageCode("MULTI", request.coverageName());
        Coverage coverage = Coverage.create(code, request.coverageName());
        if (request.status() == CoverageStatus.OPTIONAL) {
            coverage.updateOptionalVehicleCategories(targetPlans.stream()
                    .map(Plan::getCategory)
                    .filter(java.util.Objects::nonNull)
                    .map(VehicleCategory::getCode)
                    .toList());
        }
        coverage = coverageRepository.save(coverage);
        saveCoverageRules(coverage, request.rules());

        for (Plan plan : targetPlans) validateBenefitAssignment(plan, coverage, request.status());
        Coverage savedCoverage = coverage;
        List<PlanCoverage> created = targetPlans.stream().map(plan -> planCoverageRepository.save(PlanCoverage.create(
                plan, savedCoverage, request.status(), request.detail(), request.monthlyPrice(), request.sortOrder(),
                request.damageCondition(), request.eventCondition(), request.towingRadiusKm()
        ))).toList();

        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN_COVERAGE", coverage.getId(), "Cobertura criada — " + coverage.getName(),
                null, coverageGroupSummary(coverage, created), username
        ));
        return created.stream().map(this::toCoverageResponse).toList();
    }

    @Transactional
    public List<CoverageAdminResponse> updateCoverage(Long id, UpdateCoverageRequest request, String username) {
        PlanCoverage source = planCoverageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cobertura do plano não encontrada."));
        Coverage coverage = source.getCoverage();
        List<PlanCoverage> currentAssignments = planCoverageRepository.findAllForAdmin().stream()
                .filter(item -> item.getCoverage().getId().equals(coverage.getId()))
                .toList();
        String old = coverageGroupSummary(coverage, currentAssignments);

        String requestedName = request.coverageName().trim();
        if (isCanonicalThirdPartyName(requestedName) && !"THIRD_PARTY_BASE".equalsIgnoreCase(coverage.getCode())) {
            throw new IllegalArgumentException(
                    "Já existe uma cobertura oficial de danos ao veículo de terceiros. Edite a cobertura atual em vez de duplicá-la."
            );
        }
        assertCoverageNameAvailable(requestedName, coverage.getId());
        if (!coverage.getName().equals(requestedName)) {
            coverage.updateAdmin(coverage.getCode(), requestedName);
            coverageRepository.save(coverage);
        }

        List<Long> selectedIds = request.planIds().stream().distinct().toList();
        if (selectedIds.isEmpty()) throw new IllegalArgumentException("Selecione pelo menos um plano.");
        List<Plan> targetPlans = selectedIds.stream().map(this::findPlan).toList();
        if (request.status() == CoverageStatus.OPTIONAL) {
            coverage.updateOptionalVehicleCategories(targetPlans.stream()
                    .map(Plan::getCategory)
                    .filter(java.util.Objects::nonNull)
                    .map(VehicleCategory::getCode)
                    .toList());
            coverageRepository.save(coverage);
        }
        for (Plan plan : targetPlans) validateBenefitAssignment(plan, coverage, request.status());

        for (PlanCoverage assignment : currentAssignments) {
            if (!selectedIds.contains(assignment.getPlan().getId())) {
                planCoverageRepository.delete(assignment);
            }
        }
        planCoverageRepository.flush();

        for (Plan plan : targetPlans) {
            PlanCoverage assignment = currentAssignments.stream()
                    .filter(item -> item.getPlan().getId().equals(plan.getId()))
                    .findFirst()
                    .filter(item -> selectedIds.contains(item.getPlan().getId()))
                    .orElse(null);
            if (assignment == null) {
                assignment = PlanCoverage.create(plan, coverage, request.status(), request.detail(), request.monthlyPrice(), request.sortOrder(),
                        request.damageCondition(), request.eventCondition(), request.towingRadiusKm());
            } else {
                assignment.update(request.status(), request.detail(), request.monthlyPrice(), request.sortOrder(),
                        request.damageCondition(), request.eventCondition(), request.towingRadiusKm());
            }
            planCoverageRepository.save(assignment);
        }

        saveCoverageRules(coverage, request.rules());
        List<PlanCoverage> updated = planCoverageRepository.findAllForAdmin().stream()
                .filter(item -> item.getCoverage().getId().equals(coverage.getId()))
                .toList();
        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN_COVERAGE", coverage.getId(), "Cobertura alterada — " + coverage.getName(),
                old, coverageGroupSummary(coverage, updated), username
        ));
        return updated.stream().map(this::toCoverageResponse).toList();
    }

    @Transactional
    public void deleteCoverage(Long id, String username) {
        PlanCoverage item = planCoverageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cobertura do plano não encontrada."));
        Coverage coverage = item.getCoverage();
        String description = item.getPlan().getName() + " — " + coverage.getName();
        String old = coverageSummary(item);
        planCoverageRepository.delete(item);
        planCoverageRepository.flush();
        // Remover o vínculo de um plano não exclui mais o benefício do catálogo.
        // O benefício só é excluído pela ação explícita na aba Benefícios.
        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN_COVERAGE", id, description + " excluída", old, null, username
        ));
    }

    @Transactional
    public void deleteCoverageGroup(Long coverageId, String username) {
        Coverage coverage = coverageRepository.findById(coverageId)
                .orElseThrow(() -> new IllegalArgumentException("Cobertura não encontrada."));
        if ("THIRD_PARTY_BASE".equalsIgnoreCase(coverage.getCode())) {
            throw new IllegalArgumentException(
                    "A cobertura-base de terceiros é estrutural e não pode ser excluída do catálogo. Os valores, a faixa FIPE e o comportamento com desconto são editáveis pelo ADM na própria tela deste benefício."
            );
        }
        List<PlanCoverage> assignments = planCoverageRepository.findAllForAdmin().stream()
                .filter(item -> item.getCoverage().getId().equals(coverageId))
                .toList();
        String old = coverageGroupSummary(coverage, assignments);
        if (!assignments.isEmpty()) {
            planCoverageRepository.deleteAll(assignments);
            planCoverageRepository.flush();
        }
        coverageRuleRepository.deleteByCoverage_Id(coverageId);
        discountBenefitRuleRepository.deleteByCoverage_Id(coverageId);
        coverageRepository.delete(coverage);
        auditRepository.save(CatalogChangeAudit.createText(
                "PLAN_COVERAGE", coverageId, "Benefício excluído — " + coverage.getName(), old, null, username
        ));
    }

    private void syncPlanBenefits(Plan plan, List<PlanBenefitSelectionRequest> requestedBenefits) {
        List<PlanBenefitSelectionRequest> selections = requestedBenefits == null ? List.of() : requestedBenefits;
        Set<Long> seenCoverageIds = new HashSet<>();
        for (PlanBenefitSelectionRequest selection : selections) {
            if (!seenCoverageIds.add(selection.coverageId())) {
                throw new IllegalArgumentException("O mesmo benefício foi selecionado mais de uma vez para este plano.");
            }
        }

        Map<Long, PlanCoverage> existing = new LinkedHashMap<>();
        for (PlanCoverage assignment : planCoverageRepository.findByPlan_Id(plan.getId())) {
            existing.put(assignment.getCoverage().getId(), assignment);
        }

        Set<Long> selectedIds = new HashSet<>(seenCoverageIds);
        for (PlanCoverage assignment : existing.values()) {
            if (!selectedIds.contains(assignment.getCoverage().getId())) {
                planCoverageRepository.delete(assignment);
            }
        }
        planCoverageRepository.flush();

        for (PlanBenefitSelectionRequest selection : selections) {
            Coverage coverage = coverageRepository.findById(selection.coverageId())
                    .orElseThrow(() -> new IllegalArgumentException("Benefício não encontrado."));
            validateBenefitAssignment(plan, coverage, selection.status());
            PlanCoverage assignment = existing.get(selection.coverageId());
            if (assignment == null) {
                assignment = PlanCoverage.create(
                        plan, coverage, selection.status(), selection.detail(), selection.monthlyPrice(), selection.sortOrder(),
                        selection.damageCondition(), selection.eventCondition(), selection.towingRadiusKm()
                );
            } else {
                DamageCondition damageCondition = selection.damageCondition() == null
                        ? assignment.getDamageCondition() : selection.damageCondition();
                EventCondition eventCondition = selection.eventCondition() == null
                        ? assignment.getEventCondition() : selection.eventCondition();
                Integer towingRadiusKm = selection.towingRadiusKm() == null
                        ? assignment.getTowingRadiusKm() : selection.towingRadiusKm();
                assignment.update(selection.status(), selection.detail(), selection.monthlyPrice(), selection.sortOrder(),
                        damageCondition, eventCondition, towingRadiusKm);
            }
            planCoverageRepository.save(assignment);
        }
    }


    private void applyOptionalVehicleCategories(Coverage coverage, List<Long> categoryIds) {
        List<Long> ids = categoryIds == null ? List.of() : categoryIds.stream().distinct().toList();
        List<VehicleCategory> selected = ids.isEmpty() ? List.of() : categoryRepository.findAllById(ids);
        if (selected.size() != ids.size()) {
            throw new IllegalArgumentException("Uma das categorias de veículo selecionadas não existe.");
        }
        coverage.updateOptionalVehicleCategories(selected.stream().map(VehicleCategory::getCode).toList());
    }

    private List<PlanCoverage> assignmentsForCoverage(Long coverageId) {
        return planCoverageRepository.findAllForAdmin().stream()
                .filter(item -> item.getCoverage().getId().equals(coverageId))
                .toList();
    }

    private void syncBenefitAssignments(Coverage coverage, List<BenefitPlanAssignmentRequest> requestedAssignments) {
        List<BenefitPlanAssignmentRequest> requests = requestedAssignments == null ? List.of() : requestedAssignments;
        Set<Long> seenPlanIds = new HashSet<>();
        for (BenefitPlanAssignmentRequest request : requests) {
            if (!seenPlanIds.add(request.planId())) {
                throw new IllegalArgumentException("O mesmo plano foi configurado mais de uma vez para este benefício.");
            }
        }

        Map<Long, PlanCoverage> existing = new LinkedHashMap<>();
        for (PlanCoverage assignment : assignmentsForCoverage(coverage.getId())) {
            existing.put(assignment.getPlan().getId(), assignment);
        }

        for (PlanCoverage assignment : existing.values()) {
            if (!seenPlanIds.contains(assignment.getPlan().getId())) {
                planCoverageRepository.delete(assignment);
            }
        }
        planCoverageRepository.flush();

        for (BenefitPlanAssignmentRequest request : requests) {
            Plan plan = findPlan(request.planId());
            validateBenefitAssignment(plan, coverage, request.status());
            PlanCoverage assignment = existing.get(plan.getId());
            if (assignment == null) {
                assignment = PlanCoverage.create(plan, coverage, request.status(), request.detail(), request.monthlyPrice(), request.sortOrder(),
                        request.damageCondition(), request.eventCondition(), request.towingRadiusKm());
            } else {
                assignment.update(request.status(), request.detail(), request.monthlyPrice(), request.sortOrder(),
                        request.damageCondition(), request.eventCondition(), request.towingRadiusKm());
            }
            planCoverageRepository.save(assignment);
        }
    }

    private void syncBenefitDiscountEffects(Coverage coverage, List<BenefitDiscountEffectRequest> requestedEffects) {
        List<BenefitDiscountEffectRequest> effects = requestedEffects == null ? List.of() : requestedEffects;
        Set<Long> seen = new HashSet<>();
        for (BenefitDiscountEffectRequest effect : effects) {
            if (!seen.add(effect.discountRuleId())) {
                throw new IllegalArgumentException("O mesmo percentual de desconto foi configurado mais de uma vez para este benefício.");
            }
        }

        // A cobertura-base de terceiros usa um editor próprio porque sua regra combina
        // plano, faixa FIPE e cenário com/sem desconto. Evitamos aplicar também esta
        // grade genérica de desconto para não haver duas fontes concorrentes.
        if ("THIRD_PARTY_BASE".equalsIgnoreCase(coverage.getCode()) && !effects.isEmpty()) {
            throw new IllegalArgumentException(
                    "A cobertura-base de terceiros possui uma regra própria editável pelo ADM. Ajuste FIPE e limites na seção específica de danos a terceiros, evitando duplicidade com as regras genéricas de desconto."
            );
        }

        discountBenefitRuleRepository.deleteByCoverage_Id(coverage.getId());
        discountBenefitRuleRepository.flush();
        for (BenefitDiscountEffectRequest effect : effects) {
            DiscountRule discountRule = discountRuleRepository.findById(effect.discountRuleId())
                    .orElseThrow(() -> new IllegalArgumentException("Regra de desconto não encontrada."));
            discountBenefitRuleRepository.save(DiscountBenefitRule.create(
                    discountRule, coverage, effect.action(), effect.detailOverride(), effect.amountOverride()
            ));
        }
    }

    private void validateBenefitAssignment(Plan plan, Coverage coverage, CoverageStatus status) {
        if (plan == null || coverage == null || status == null) return;
        String coverageCode = coverage.getCode() == null ? "" : coverage.getCode().trim().toUpperCase(Locale.ROOT);
        if (Quotation.isDiscountExcludedBenefit(coverageCode)) {
            if (!isCarOrUtilityCompletePlan(plan) || status != CoverageStatus.INCLUDED) {
                throw new IllegalArgumentException(
                        "Fenômenos da Natureza e Pequenos Reparos são benefícios integrados somente aos planos completos de carro e utilitário."
                );
            }
        }
        if (status == CoverageStatus.OPTIONAL) {
            String categoryCode = plan.getCategory() == null ? "" : plan.getCategory().getCode();
            if (!coverage.allowsOptionalForVehicleCategory(categoryCode)) {
                throw new IllegalArgumentException(
                        "Este benefício não está liberado como adicional para o tipo de veículo do plano " + plan.getName() + "."
                );
            }
        }
    }

    private boolean isCarOrUtilityCompletePlan(Plan plan) {
        if (plan == null || plan.getCode() == null) return false;
        String code = plan.getCode().trim().toUpperCase(Locale.ROOT);
        return "CAR_COMPLETO".equals(code) || "UTILITY_COMPLETO".equals(code);
    }

    private void saveCoverageRules(Coverage coverage, List<CoverageRuleRequest> requests) {
        if (coverage != null && "THIRD_PARTY_BASE".equalsIgnoreCase(coverage.getCode())) {
            // THIRD_PARTY_BASE é editado pelo painel próprio de regras de terceiros.
            // Não gravamos também em coverage_rules para manter uma única fonte de verdade.
            return;
        }
        coverageRuleRepository.deleteByCoverage_Id(coverage.getId());
        if (requests == null || requests.isEmpty()) return;

        for (int i = 0; i < requests.size(); i++) {
            CoverageRuleRequest current = requests.get(i);
            if (current.maxFipe() != null && current.maxFipe().compareTo(current.minFipe()) < 0) {
                throw new IllegalArgumentException("Em uma regra de cobertura, o FIPE máximo deve ser maior ou igual ao mínimo.");
            }
            String currentCategory = current.categoryCode() == null || current.categoryCode().isBlank() ? "*" : current.categoryCode().trim().toUpperCase(Locale.ROOT);
            BigDecimal currentMax = current.maxFipe() == null ? new BigDecimal("999999999999.99") : current.maxFipe();
            for (int j = i + 1; j < requests.size(); j++) {
                CoverageRuleRequest other = requests.get(j);
                String otherCategory = other.categoryCode() == null || other.categoryCode().isBlank() ? "*" : other.categoryCode().trim().toUpperCase(Locale.ROOT);
                if (!currentCategory.equals(otherCategory)) continue;
                BigDecimal otherMax = other.maxFipe() == null ? new BigDecimal("999999999999.99") : other.maxFipe();
                boolean overlaps = current.minFipe().compareTo(otherMax) <= 0 && other.minFipe().compareTo(currentMax) <= 0;
                if (overlaps) {
                    throw new IllegalArgumentException("Há regras de cobertura com faixas FIPE sobrepostas para a mesma categoria.");
                }
            }
        }

        int fallbackOrder = 100;
        for (CoverageRuleRequest request : requests) {
            CoverageRule rule = CoverageRule.create(
                    coverage, request.categoryCode(), request.minFipe(), request.maxFipe(),
                    request.normalAmount(), request.discountedAmount(),
                    request.sortOrder() == null ? fallbackOrder : request.sortOrder()
            );
            coverageRuleRepository.save(rule);
            fallbackOrder += 10;
        }
    }

    private String coverageGroupSummary(Coverage coverage, List<PlanCoverage> assignments) {
        String plans = assignments.stream().map(item -> item.getPlan().getName()).distinct().sorted().collect(java.util.stream.Collectors.joining(", "));
        return "benefício=" + coverage.getName() + "; planos=" + plans + "; regras=" + coverageRuleRepository.findByCoverage_IdOrderBySortOrderAscIdAsc(coverage.getId()).size();
    }

    @Transactional(readOnly = true)
    public List<AuditResponse> audit() {
        return auditRepository.findAllByOrderByChangedAtDesc().stream().map(item -> new AuditResponse(
                item.getId(), item.getItemType(), item.getItemId(), item.getItemKey(), item.getDescription(),
                item.getOldValue(), item.getNewValue(), hideInternalCode(item.getOldText()), hideInternalCode(item.getNewText()),
                item.getChangedBy(), item.getChangedAt()
        )).toList();
    }

    private CategoryResponse toCategoryResponse(VehicleCategory item) {
        return new CategoryResponse(item.getId(), item.getCode(), item.getName(), Boolean.TRUE.equals(item.getActive()));
    }

    private PriceRangeResponse toPriceResponse(PriceRange item) {
        return new PriceRangeResponse(
                item.getId(), item.getPlan().getId(), item.getPlan().getName(), item.getPlan().getCategory().getName(),
                item.getPlan().getRegion().name(), item.getPricingRegion(), item.getPlan().getMotorcycleOrigin(),
                item.getMinValue(), item.getMaxValue(), item.getMonthlyPrice()
        );
    }

    private String promotionalMotorcyclePriceSummary(PromotionalMotorcyclePrice item) {
        return "faixa=" + item.getLabel()
                + "; cc=" + item.getMinCc() + "-" + item.getMaxCc()
                + "; fipe=" + item.getMinFipe() + "-" + (item.getMaxFipe() == null ? "sem limite" : item.getMaxFipe())
                + "; mensalidade=" + item.getMonthlyPrice();
    }

    private PromotionalMotorcyclePriceResponse toPromotionalMotorcyclePriceResponse(PromotionalMotorcyclePrice item) {
        return new PromotionalMotorcyclePriceResponse(
                item.getId(), item.getTierCode(), item.getLabel(), item.getMinCc(), item.getMaxCc(),
                item.getMinFipe(), item.getMaxFipe(), item.getMonthlyPrice()
        );
    }

    private PlanAdminResponse toPlanResponse(Plan plan) {
        return new PlanAdminResponse(
                plan.getId(), plan.getName(), plan.getSubtitle(), plan.getCategory().getId(),
                plan.getCategory().getName(), plan.getRegion(), plan.getMotorcycleOrigin(),
                plan.getDisplayOrder(), Boolean.TRUE.equals(plan.getActive()),
                plan.getExtraAbove(), plan.getExtraStep(), plan.getExtraIncrement(), plan.getExtraBasePrice(),
                plan.getTrackerRequiredAbove(), plan.getTrackerInstallationFee(), plan.getTrackerMonthlyFee()
        );
    }

    private BenefitAdminResponse toBenefitResponse(Coverage coverage, List<PlanCoverage> assignments) {
        List<CoverageRuleAdminResponse> rules = coverageRuleRepository
                .findByCoverage_IdOrderBySortOrderAscIdAsc(coverage.getId())
                .stream()
                .map(rule -> new CoverageRuleAdminResponse(
                        rule.getId(), rule.getCategoryCode(), rule.getMinFipe(), rule.getMaxFipe(),
                        rule.getNormalAmount(), rule.getDiscountedAmount(), rule.getSortOrder()
                ))
                .toList();
        List<BenefitAssignmentResponse> assignmentResponses = assignments.stream()
                .sorted(Comparator.comparing(item -> item.getPlan().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(item -> new BenefitAssignmentResponse(
                        item.getId(), item.getPlan().getId(), item.getPlan().getName(),
                        item.getPlan().getCategory().getId(), item.getPlan().getCategory().getName(),
                        item.getStatus(), item.getDetail(), item.getMonthlyPrice(), item.getSortOrder(),
                        item.getDamageCondition(), item.getEventCondition(), item.getTowingRadiusKm()
                ))
                .toList();
        Set<String> allowedCodes = coverage.getOptionalVehicleCategoryCodes();
        List<VehicleCategory> allCategories = categoryRepository.findAllByOrderByNameAsc();
        List<Long> allowedIds = allCategories.stream()
                .filter(category -> allowedCodes.contains(category.getCode().trim().toUpperCase(Locale.ROOT)))
                .map(VehicleCategory::getId)
                .toList();
        List<String> codes = allCategories.stream()
                .filter(category -> allowedCodes.contains(category.getCode().trim().toUpperCase(Locale.ROOT)))
                .map(VehicleCategory::getCode)
                .toList();
        List<BenefitDiscountEffectResponse> discountEffects = discountBenefitRuleRepository.findByCoverage_Id(coverage.getId()).stream()
                .sorted(Comparator.comparing(item -> item.getDiscountRule().getPercent()))
                .map(item -> new BenefitDiscountEffectResponse(
                        item.getDiscountRule().getId(), item.getDiscountRule().getName(), item.getDiscountRule().getPercent(),
                        Boolean.TRUE.equals(item.getDiscountRule().getActive()), item.getAction(), item.getDetailOverride(), item.getAmountOverride()
                ))
                .toList();
        return new BenefitAdminResponse(coverage.getId(), coverage.getCode(), coverage.getName(), allowedIds, codes, rules, assignmentResponses, discountEffects);
    }

    private CoverageAdminResponse toCoverageResponse(PlanCoverage item) {
        List<CoverageRuleAdminResponse> rules = coverageRuleRepository
                .findByCoverage_IdOrderBySortOrderAscIdAsc(item.getCoverage().getId())
                .stream()
                .map(rule -> new CoverageRuleAdminResponse(
                        rule.getId(), rule.getCategoryCode(), rule.getMinFipe(), rule.getMaxFipe(),
                        rule.getNormalAmount(), rule.getDiscountedAmount(), rule.getSortOrder()
                ))
                .toList();
        return new CoverageAdminResponse(
                item.getId(), item.getCoverage().getId(), item.getCoverage().getCode(),
                item.getPlan().getId(), item.getPlan().getName(),
                item.getPlan().getCategory().getName(), item.getPlan().getRegion().name(), item.getPlan().getMotorcycleOrigin(),
                item.getCoverage().getName(), item.getStatus(),
                item.getDetail(), item.getMonthlyPrice(), item.getSortOrder(),
                item.getDamageCondition(), item.getEventCondition(), item.getTowingRadiusKm(), rules
        );
    }

    private void validateRange(Long planId, PricingRegion pricingRegion, Long ignoredId, BigDecimal min, BigDecimal max) {
        if (max.compareTo(min) < 0) throw new IllegalArgumentException("O valor FIPE máximo deve ser maior ou igual ao mínimo.");
        PricingRegion effectiveRegion = pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
        boolean overlaps = priceRepository.findByPlan_IdAndPricingRegion(planId, effectiveRegion).stream().anyMatch(item ->
                (ignoredId == null || !item.getId().equals(ignoredId))
                        && item.getMinValue().compareTo(max) <= 0
                        && item.getMaxValue().compareTo(min) >= 0
        );
        if (overlaps) throw new IllegalArgumentException("A faixa FIPE informada se sobrepõe a outra faixa deste plano nesta região.");
    }

    private Plan findPlan(Long id) {
        return planRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Plano não encontrado."));
    }

    private VehicleCategory findCategory(Long id) {
        return categoryRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Categoria não encontrada."));
    }

    private String priceSummary(PriceRange range) {
        return "plano=" + range.getPlan().getName()
                + "; regiãoTabela=" + range.getPricingRegion()
                + "; mínimo=" + range.getMinValue()
                + "; máximo=" + range.getMaxValue()
                + "; mensal=" + range.getMonthlyPrice();
    }

    private String planSummary(Plan plan) {
        return "nome=" + plan.getName()
                + "; categoria=" + plan.getCategory().getName()
                + "; abrangência=" + plan.getRegion()
                + "; origemMoto=" + value(plan.getMotorcycleOrigin())
                + "; subtítulo=" + value(plan.getSubtitle())
                + "; ordem=" + plan.getDisplayOrder()
                + "; ativo=" + plan.getActive()
                + "; extraAcima=" + value(plan.getExtraAbove())
                + "; extraIntervalo=" + value(plan.getExtraStep())
                + "; extraAcréscimo=" + value(plan.getExtraIncrement())
                + "; extraBase=" + value(plan.getExtraBasePrice())
                + "; rastreadorAcima=" + value(plan.getTrackerRequiredAbove())
                + "; rastreadorInstalação=" + value(plan.getTrackerInstallationFee())
                + "; rastreadorMensal=" + value(plan.getTrackerMonthlyFee());
    }

    private String coverageSummary(PlanCoverage item) {
        return "plano=" + item.getPlan().getName()
                + "; nome=" + item.getCoverage().getName()
                + "; status=" + item.getStatus()
                + "; detalhe=" + value(item.getDetail())
                + "; mensal=" + value(item.getMonthlyPrice())
                + "; ordem=" + item.getSortOrder()
                + "; dano=" + value(item.getDamageCondition())
                + "; origemEvento=" + value(item.getEventCondition())
                + "; reboqueKm=" + value(item.getTowingRadiusKm());
    }

    private String value(Object value) { return value == null ? "—" : value.toString(); }

    private String hideInternalCode(String text) {
        if (text == null || text.isBlank()) return text;
        return text
                .replaceAll("(?i)(^|;\\s*)código=[^;]*(;\\s*)?", "$1")
                .replaceAll("^;\\s*|;\\s*$", "")
                .replaceAll(";\\s*;", "; ")
                .trim();
    }

    private String uniquePlanCode(String categoryCode, MotorcycleOrigin motorcycleOrigin, String name) {
        String originPart = motorcycleOrigin == null ? "NACIONAL" : motorcycleOrigin.name();
        String base = normalizeCode(categoryCode + "_" + originPart + "_" + name, 80, "Código do plano inválido.");
        String candidate = base;
        int suffix = 2;
        while (planRepository.existsByCode(candidate)) {
            String ending = "_" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 80 - ending.length())) + ending;
        }
        return candidate;
    }


    private void assertCoverageNameAvailable(String name, Long currentCoverageId) {
        String normalized = normalizeCoverageName(name);
        boolean duplicated = coverageRepository.findAll().stream()
                .filter(item -> currentCoverageId == null || !item.getId().equals(currentCoverageId))
                .anyMatch(item -> normalizeCoverageName(item.getName()).equals(normalized));
        if (duplicated) {
            throw new IllegalArgumentException(
                    "Já existe uma cobertura com este nome. Abra a cobertura existente e marque nela todos os planos desejados, em vez de criar um nome repetido."
            );
        }
    }

    private String normalizeCoverageName(String name) {
        if (name == null) return "";
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private boolean isCanonicalThirdPartyName(String name) {
        if (name == null || name.isBlank()) return false;
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return "danos ao veiculo de terceiros".equals(normalized);
    }

    private String uniqueCoverageCode(String planCode, String coverageName) {
        String base = normalizeCode(planCode + "_" + coverageName, 80, "Código da cobertura inválido.");
        String candidate = base;
        int suffix = 2;
        while (coverageRepository.existsByCode(candidate)) {
            String ending = "_" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 80 - ending.length())) + ending;
        }
        return candidate;
    }

    private String normalizeCode(String value, int maxLength, String error) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) throw new IllegalArgumentException(error);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
