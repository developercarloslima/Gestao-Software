package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.AdminDtos.*;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminDiscountService {
    private final DiscountRuleRepository discountRepository;
    private final PlanRepository planRepository;
    private final CoverageRepository coverageRepository;
    private final CatalogChangeAuditRepository auditRepository;

    public AdminDiscountService(
            DiscountRuleRepository discountRepository,
            PlanRepository planRepository,
            CoverageRepository coverageRepository,
            CatalogChangeAuditRepository auditRepository
    ) {
        this.discountRepository = discountRepository;
        this.planRepository = planRepository;
        this.coverageRepository = coverageRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public List<DiscountRuleAdminResponse> list() {
        return discountRepository.findAllByOrderByDisplayOrderAscPercentAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public DiscountRuleAdminResponse create(UpsertDiscountRuleRequest request, String username) {
        if (discountRepository.existsByPercent(request.percent())) {
            throw new IllegalArgumentException("Já existe um desconto de " + request.percent() + "% cadastrado.");
        }
        DiscountRule rule = DiscountRule.create(
                request.name(), request.percent(), request.active(), request.displayOrder(),
                request.minVehicleAgeYears(), request.maxVehicleAgeYears(), request.minFipe(), request.maxFipe(),
                request.requiresRearWindowPerforation(), request.applyToBase(), request.applyToMandatoryFee(),
                request.applyToOptionals(), request.notes()
        );
        applyRelations(rule, request);
        rule = discountRepository.save(rule);
        auditRepository.save(CatalogChangeAudit.createText(
                "DISCOUNT_RULE", rule.getId(), "Desconto criado — " + rule.getPercent() + "%",
                null, summary(rule), username
        ));
        return toResponse(rule);
    }

    @Transactional
    public DiscountRuleAdminResponse update(Long id, UpsertDiscountRuleRequest request, String username) {
        DiscountRule rule = discountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Desconto não encontrado."));
        if (discountRepository.existsByPercentAndIdNot(request.percent(), id)) {
            throw new IllegalArgumentException("Já existe outro desconto de " + request.percent() + "% cadastrado.");
        }
        String old = summary(rule);
        rule.update(
                request.name(), request.percent(), request.active(), request.displayOrder(),
                request.minVehicleAgeYears(), request.maxVehicleAgeYears(), request.minFipe(), request.maxFipe(),
                request.requiresRearWindowPerforation(), request.applyToBase(), request.applyToMandatoryFee(),
                request.applyToOptionals(), request.notes()
        );
        applyRelations(rule, request);
        rule = discountRepository.save(rule);
        auditRepository.save(CatalogChangeAudit.createText(
                "DISCOUNT_RULE", rule.getId(), "Desconto alterado — " + rule.getPercent() + "%",
                old, summary(rule), username
        ));
        return toResponse(rule);
    }

    @Transactional
    public void delete(Long id, String username) {
        DiscountRule rule = discountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Desconto não encontrado."));
        String old = summary(rule);
        int percent = rule.getPercent();
        discountRepository.delete(rule);
        auditRepository.save(CatalogChangeAudit.createText(
                "DISCOUNT_RULE", id, "Desconto excluído — " + percent + "%", old, null, username
        ));
    }

    private void applyRelations(DiscountRule rule, UpsertDiscountRuleRequest request) {
        List<Long> uniquePlanIds = request.planIds().stream().distinct().toList();
        List<Plan> selectedPlans = planRepository.findAllById(uniquePlanIds);
        if (selectedPlans.size() != uniquePlanIds.size()) {
            throw new IllegalArgumentException("Um ou mais planos selecionados não existem mais.");
        }
        rule.replacePlans(selectedPlans);

        Map<Long, DiscountBenefitRuleRequest> uniqueBenefitRequests = new LinkedHashMap<>();
        for (DiscountBenefitRuleRequest item : request.benefitRules()) {
            if (uniqueBenefitRequests.putIfAbsent(item.coverageId(), item) != null) {
                throw new IllegalArgumentException("A mesma cobertura não pode possuir duas ações no mesmo desconto.");
            }
        }
        Map<Long, Coverage> coverageById = coverageRepository.findAllById(uniqueBenefitRequests.keySet()).stream()
                .collect(Collectors.toMap(Coverage::getId, Function.identity()));
        if (coverageById.size() != uniqueBenefitRequests.size()) {
            throw new IllegalArgumentException("Uma ou mais coberturas selecionadas não existem mais.");
        }
        boolean changesProtectedThirdParty = coverageById.values().stream()
                .anyMatch(coverage -> coverage != null && "THIRD_PARTY_BASE".equalsIgnoreCase(coverage.getCode()));
        if (changesProtectedThirdParty) {
            throw new IllegalArgumentException(
                    "A cobertura-base de terceiros segue a regra fixa FIPE/desconto (R$ 50 mil/R$ 100 mil) e não pode receber uma regra administrativa de desconto."
            );
        }
        List<DiscountBenefitRule> benefitRules = uniqueBenefitRequests.values().stream()
                .map(item -> DiscountBenefitRule.create(
                        rule, coverageById.get(item.coverageId()), item.action(), item.detailOverride(), item.amountOverride()
                ))
                .toList();
        rule.replaceBenefitRules(benefitRules);
    }

    private DiscountRuleAdminResponse toResponse(DiscountRule rule) {
        List<Plan> sortedPlans = rule.getPlans().stream()
                .sorted(Comparator.comparing(Plan::getDisplayOrder).thenComparing(Plan::getName))
                .toList();
        List<DiscountBenefitRuleResponse> benefits = rule.getBenefitRules().stream()
                .sorted(Comparator.comparing(item -> item.getCoverage().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(item -> new DiscountBenefitRuleResponse(
                        item.getId(), item.getCoverage().getId(), item.getCoverage().getCode(), item.getCoverage().getName(),
                        item.getAction(), item.getDetailOverride(), item.getAmountOverride()
                ))
                .toList();
        return new DiscountRuleAdminResponse(
                rule.getId(), rule.getName(), rule.getPercent(), Boolean.TRUE.equals(rule.getActive()), rule.getDisplayOrder(),
                rule.getMinVehicleAgeYears(), rule.getMaxVehicleAgeYears(), rule.getMinFipe(), rule.getMaxFipe(),
                Boolean.TRUE.equals(rule.getRequiresRearWindowPerforation()),
                Boolean.TRUE.equals(rule.getApplyToBase()), Boolean.TRUE.equals(rule.getApplyToMandatoryFee()),
                Boolean.TRUE.equals(rule.getApplyToOptionals()), rule.getNotes(),
                sortedPlans.stream().map(Plan::getId).toList(), sortedPlans.stream().map(Plan::getName).toList(), benefits
        );
    }

    private String summary(DiscountRule rule) {
        String plans = rule.getPlans().stream().map(Plan::getName).sorted().collect(Collectors.joining(", "));
        String benefits = rule.getBenefitRules().stream()
                .map(item -> item.getCoverage().getName() + "=" + item.getAction())
                .sorted().collect(Collectors.joining(", "));
        return "nome=" + rule.getName()
                + "; percentual=" + rule.getPercent() + "%"
                + "; ativo=" + rule.getActive()
                + "; idade=" + Objects.toString(rule.getMinVehicleAgeYears(), "-") + ".." + Objects.toString(rule.getMaxVehicleAgeYears(), "-")
                + "; FIPE=" + Objects.toString(rule.getMinFipe(), "-") + ".." + Objects.toString(rule.getMaxFipe(), "-")
                + "; perfurado=" + rule.getRequiresRearWindowPerforation()
                + "; aplicaBase=" + rule.getApplyToBase()
                + "; aplicaTaxas=" + rule.getApplyToMandatoryFee()
                + "; aplicaAdicionais=" + rule.getApplyToOptionals()
                + "; planos=[" + plans + "]"
                + "; benefícios=[" + benefits + "]";
    }
}
