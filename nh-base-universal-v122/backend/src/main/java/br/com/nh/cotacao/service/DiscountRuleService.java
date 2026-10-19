package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.DiscountRuleRepository;
import br.com.nh.cotacao.repository.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Motor comercial centralizado de descontos. Nenhuma regra de 5/10/15/30 fica
 * presa no código: o painel Admin define percentuais, planos elegíveis,
 * idade/FIPE, perfurado, componentes do preço e efeitos em benefícios.
 */
@Service
public class DiscountRuleService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Maceio");

    private final DiscountRuleRepository discountRuleRepository;
    private final PlanRepository planRepository;

    public DiscountRuleService(DiscountRuleRepository discountRuleRepository, PlanRepository planRepository) {
        this.discountRuleRepository = discountRuleRepository;
        this.planRepository = planRepository;
    }

    public record Availability(
            DiscountRule rule,
            boolean available,
            String unavailableReason,
            RearWindowBranding requiredBranding,
            String conditionText
    ) {}

    public record PriceResult(
            BigDecimal subtotal,
            BigDecimal discountableAmount,
            BigDecimal discountValue,
            BigDecimal finalValue
    ) {}

    @Transactional(readOnly = true)
    public List<Availability> optionsForPlan(Plan plan, BigDecimal fipe, Integer manufactureYear) {
        if (plan == null || plan.getId() == null) return List.of();
        return discountRuleRepository.findAllByOrderByDisplayOrderAscPercentAsc().stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getActive()))
                .filter(rule -> isAssignedToPlan(rule, plan.getId()))
                .map(rule -> availability(rule, fipe, manufactureYear))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Availability> optionsForPlanCode(String planCode, BigDecimal fipe, Integer manufactureYear) {
        Plan plan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new IllegalArgumentException("Plano não encontrado para consultar descontos."));
        return optionsForPlan(plan, fipe, manufactureYear);
    }

    @Transactional(readOnly = true)
    public List<Availability> optionsForQuotation(Quotation quotation, BigDecimal fipe, Integer manufactureYear) {
        if (quotation == null || quotation.getSelectedPlanCode() == null || quotation.getSelectedPlanCode().isBlank()) {
            return List.of();
        }
        return optionsForPlanCode(quotation.getSelectedPlanCode(), fipe, manufactureYear);
    }

    /**
     * Consulta tolerante usada somente para reconstruir a visualização de cotações
     * históricas. Se a regra tiver sido excluída/desativada ou deixar de ser
     * elegível com o catálogo atual, o histórico continua abrindo sem reaplicar
     * uma regra que já não existe. Novas alterações usam requireApplicable().
     */
    @Transactional(readOnly = true)
    public Optional<DiscountRule> findApplicableQuietly(
            Quotation quotation, Integer requestedPercent, BigDecimal fipe, Integer manufactureYear
    ) {
        int percent = requestedPercent == null ? 0 : requestedPercent;
        if (percent == 0 || quotation == null || quotation.getSelectedPlanCode() == null || quotation.getSelectedPlanCode().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(requireApplicable(
                    quotation, percent, fipe, manufactureYear, RearWindowBranding.ANY_PERFORATED
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public DiscountRule requireApplicable(
            Plan plan, Integer requestedPercent, BigDecimal fipe, Integer manufactureYear, RearWindowBranding requestedBranding
    ) {
        int percent = requestedPercent == null ? 0 : requestedPercent;
        if (percent == 0) return null;
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Informe um desconto entre 0% e 100%.");

        DiscountRule rule = discountRuleRepository.findByPercent(percent)
                .orElseThrow(() -> new IllegalArgumentException("O desconto de " + percent + "% não está cadastrado."));
        if (!Boolean.TRUE.equals(rule.getActive())) {
            throw new IllegalArgumentException("O desconto de " + percent + "% está desativado pelo Administrador.");
        }
        if (plan == null || plan.getId() == null || !isAssignedToPlan(rule, plan.getId())) {
            throw new IllegalArgumentException("O desconto de " + percent + "% não está liberado para este plano.");
        }
        Availability availability = availability(rule, fipe, manufactureYear);
        if (!availability.available()) throw new IllegalArgumentException(availability.unavailableReason());

        validateBranding(rule, requestedBranding);
        return rule;
    }

    @Transactional(readOnly = true)
    public DiscountRule requireApplicable(
            Quotation quotation, Integer requestedPercent, BigDecimal fipe, Integer manufactureYear, RearWindowBranding requestedBranding
    ) {
        if (quotation == null || quotation.getSelectedPlanCode() == null || quotation.getSelectedPlanCode().isBlank()) {
            if ((requestedPercent == null ? 0 : requestedPercent) == 0) return null;
            throw new IllegalArgumentException("A cotação não possui um plano atual para validar o desconto.");
        }
        Plan plan = planRepository.findByCode(quotation.getSelectedPlanCode())
                .orElseThrow(() -> new IllegalArgumentException("O plano da cotação não existe mais no catálogo."));
        return requireApplicable(plan, requestedPercent, fipe, manufactureYear, requestedBranding);
    }

    public RearWindowBranding normalizedBranding(DiscountRule rule, RearWindowBranding requestedBranding) {
        if (rule == null || !Boolean.TRUE.equals(rule.getRequiresRearWindowPerforation())) {
            return RearWindowBranding.NOT_APPLICABLE;
        }
        RearWindowBranding branding = requestedBranding == null ? RearWindowBranding.ANY_PERFORATED : requestedBranding;
        if (branding == RearWindowBranding.NOT_APPLICABLE) {
            throw new IllegalArgumentException("Este desconto exige perfurado no vigia traseiro.");
        }
        return branding;
    }

    public PriceResult calculate(
            DiscountRule rule, BigDecimal base, BigDecimal mandatoryMonthlyFee, BigDecimal optionalsMonthlyValue
    ) {
        BigDecimal normalizedBase = money(base);
        BigDecimal normalizedMandatory = money(mandatoryMonthlyFee);
        BigDecimal normalizedOptionals = money(optionalsMonthlyValue);
        BigDecimal subtotal = normalizedBase.add(normalizedMandatory).add(normalizedOptionals).setScale(2, RoundingMode.HALF_UP);
        if (rule == null) return new PriceResult(subtotal, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), subtotal);

        BigDecimal discountable = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(rule.getApplyToBase())) discountable = discountable.add(normalizedBase);
        if (Boolean.TRUE.equals(rule.getApplyToMandatoryFee())) discountable = discountable.add(normalizedMandatory);
        if (Boolean.TRUE.equals(rule.getApplyToOptionals())) discountable = discountable.add(normalizedOptionals);
        discountable = discountable.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = discountable.multiply(BigDecimal.valueOf(rule.getPercent()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal finalValue = subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new PriceResult(subtotal, discountable, discount, finalValue);
    }

    @Transactional(readOnly = true)
    public Set<String> applyBenefitRules(Quotation quotation, Collection<String> requestedCodes, DiscountRule rule) {
        if (quotation == null || quotation.getSelectedPlanCode() == null || quotation.getSelectedPlanCode().isBlank()) {
            return normalizeCodes(requestedCodes);
        }
        Plan plan = planRepository.findByCode(quotation.getSelectedPlanCode())
                .orElseThrow(() -> new IllegalArgumentException("O plano da cotação não existe mais no catálogo."));
        return applyBenefitRules(plan, requestedCodes, rule);
    }

    public Set<String> applyBenefitRules(Plan plan, Collection<String> requestedCodes, DiscountRule rule) {
        LinkedHashSet<String> selected = (requestedCodes == null ? List.<String>of() : requestedCodes).stream()
                .filter(Objects::nonNull)
                .map(this::normalizeCode)
                .filter(code -> !code.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (plan == null) return selected;

        // Regra estrutural do produto: estes benefícios só pertencem a plano
        // completo e nunca entram quando há desconto, mesmo que uma regra do
        // painel administrativo tente forçar a inclusão.
        if (!isCompletePlan(plan)) {
            selected.remove("NATURAL_PHENOMENA");
            selected.remove("SMALL_REPAIRS");
        }
        if (rule == null) return selected;
        if (rule.getPercent() != null && rule.getPercent() > 0) {
            selected.remove("NATURAL_PHENOMENA");
            selected.remove("SMALL_REPAIRS");
        }

        String planCategoryCode = plan.getCategory() == null ? "" : plan.getCategory().getCode();
        String normalizedPlanCode = plan.getCode() == null ? "" : plan.getCode().trim().toUpperCase(Locale.ROOT);
        Set<String> planCoverageCodes = plan.getCoverages().stream()
                .filter(item -> item.getCoverage() != null && item.getCoverage().getCode() != null)
                .filter(item -> item.getStatus() != CoverageStatus.OPTIONAL
                        || item.getCoverage().allowsOptionalForVehicleCategory(planCategoryCode))
                .filter(item -> !Quotation.isDiscountExcludedBenefit(item.getCoverage().getCode())
                        || (("CAR_COMPLETO".equals(normalizedPlanCode) || "UTILITY_COMPLETO".equals(normalizedPlanCode))
                            && item.getStatus() == CoverageStatus.INCLUDED))
                .map(item -> normalizeCode(item.getCoverage().getCode()))
                .collect(Collectors.toSet());

        for (DiscountBenefitRule benefitRule : rule.getBenefitRules()) {
            String code = benefitRule.getCoverage() == null ? "" : normalizeCode(benefitRule.getCoverage().getCode());
            if (code.isBlank() || !planCoverageCodes.contains(code)) continue;
            if (isProtectedThirdPartyRule(plan, code)) continue;
            if (benefitRule.getAction() == DiscountBenefitAction.REMOVE) selected.remove(code);
            if (benefitRule.getAction() == DiscountBenefitAction.FORCE_INCLUDE
                    && !Quotation.isDiscountExcludedBenefit(code)) selected.add(code);
        }
        // Segunda barreira: impede qualquer configuração administrativa de
        // reintroduzir os dois benefícios em uma cotação com desconto.
        if (rule.getPercent() != null && rule.getPercent() > 0) {
            selected.remove("NATURAL_PHENOMENA");
            selected.remove("SMALL_REPAIRS");
        }
        return selected;
    }


    private boolean isProtectedThirdPartyRule(Plan plan, String coverageCode) {
        if (plan == null || plan.getCategory() == null || coverageCode == null) return false;
        if (!"THIRD_PARTY_BASE".equals(normalizeCode(coverageCode))) return false;
        String category = normalizeCode(plan.getCategory().getCode());
        return "CAR_NATIONAL".equals(category) || "CAR_IMPORTED".equals(category) || "UTILITY".equals(category);
    }

    private boolean isCompletePlan(Plan plan) {
        if (plan == null || plan.getCode() == null) return false;
        String code = plan.getCode().trim().toUpperCase(Locale.ROOT);
        return "CAR_COMPLETO".equals(code) || "UTILITY_COMPLETO".equals(code);
    }

    public Optional<DiscountBenefitRule> benefitRule(DiscountRule rule, String coverageCode) {
        if (rule == null || coverageCode == null) return Optional.empty();
        String code = normalizeCode(coverageCode);
        return rule.getBenefitRules().stream()
                .filter(item -> item.getCoverage() != null && code.equals(normalizeCode(item.getCoverage().getCode())))
                .findFirst();
    }

    public String adjustedBenefitDetail(DiscountRule rule, String coverageCode, String currentDetail) {
        Optional<DiscountBenefitRule> match = benefitRule(rule, coverageCode);
        if (match.isEmpty()) return currentDetail;
        DiscountBenefitRule benefitRule = match.get();
        if (benefitRule.getDetailOverride() != null && !benefitRule.getDetailOverride().isBlank()) {
            return benefitRule.getDetailOverride();
        }
        if (benefitRule.getAmountOverride() != null) {
            String amount = coverageAmountLabel(benefitRule.getAmountOverride());
            if (currentDetail == null || currentDetail.isBlank()) return amount;
            java.util.regex.Matcher moneyMatcher = java.util.regex.Pattern
                    .compile("R\\$\\s*[0-9.]+(?:,[0-9]{2})?(?:\\s*mil)?", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(currentDetail);
            if (moneyMatcher.find()) return moneyMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(amount));
            return currentDetail + " • Limite: " + amount;
        }
        return currentDetail;
    }

    public String conditionText(DiscountRule rule) {
        if (rule == null) return "";
        List<String> parts = new ArrayList<>();
        if (rule.getMinVehicleAgeYears() != null && rule.getMaxVehicleAgeYears() != null) {
            parts.add("veículos entre " + rule.getMinVehicleAgeYears() + " e " + rule.getMaxVehicleAgeYears() + " anos de uso");
        } else if (rule.getMaxVehicleAgeYears() != null) {
            parts.add("veículos com até " + rule.getMaxVehicleAgeYears() + " anos de uso");
        } else if (rule.getMinVehicleAgeYears() != null) {
            parts.add("veículos a partir de " + rule.getMinVehicleAgeYears() + " anos de uso");
        }
        if (rule.getMinFipe() != null && rule.getMaxFipe() != null) {
            parts.add("FIPE entre " + currency(rule.getMinFipe()) + " e " + currency(rule.getMaxFipe()));
        } else if (rule.getMinFipe() != null) {
            parts.add("FIPE a partir de " + currency(rule.getMinFipe()));
        } else if (rule.getMaxFipe() != null) {
            parts.add("FIPE até " + currency(rule.getMaxFipe()));
        }
        if (Boolean.TRUE.equals(rule.getRequiresRearWindowPerforation())) {
            parts.add("exige perfurado no vigia traseiro");
        }
        return String.join(" · ", parts);
    }

    private Availability availability(DiscountRule rule, BigDecimal fipe, Integer manufactureYear) {
        String reason = null;
        if (manufactureYear == null && (rule.getMinVehicleAgeYears() != null || rule.getMaxVehicleAgeYears() != null)) {
            reason = "Informe o ano/modelo do veículo para validar o desconto de " + rule.getPercent() + "%.";
        }
        if (reason == null && manufactureYear != null) {
            int age = Math.max(0, Year.now(BUSINESS_ZONE).getValue() - manufactureYear);
            if (rule.getMinVehicleAgeYears() != null && age < rule.getMinVehicleAgeYears()) {
                reason = "O desconto de " + rule.getPercent() + "% exige veículo com pelo menos " + rule.getMinVehicleAgeYears() + " anos de uso.";
            }
            if (reason == null && rule.getMaxVehicleAgeYears() != null && age > rule.getMaxVehicleAgeYears()) {
                reason = "O desconto de " + rule.getPercent() + "% é permitido somente para veículos com até " + rule.getMaxVehicleAgeYears() + " anos de uso.";
            }
        }
        if (reason == null && fipe != null && rule.getMinFipe() != null && fipe.compareTo(rule.getMinFipe()) < 0) {
            reason = "O desconto de " + rule.getPercent() + "% exige FIPE a partir de " + currency(rule.getMinFipe()) + ".";
        }
        if (reason == null && fipe != null && rule.getMaxFipe() != null && fipe.compareTo(rule.getMaxFipe()) > 0) {
            reason = "O desconto de " + rule.getPercent() + "% é permitido somente até FIPE de " + currency(rule.getMaxFipe()) + ".";
        }
        RearWindowBranding branding = Boolean.TRUE.equals(rule.getRequiresRearWindowPerforation())
                ? RearWindowBranding.ANY_PERFORATED : RearWindowBranding.NOT_APPLICABLE;
        return new Availability(rule, reason == null, reason, branding, conditionText(rule));
    }

    private boolean isAssignedToPlan(DiscountRule rule, Long planId) {
        return rule.getPlans().stream().anyMatch(plan -> Objects.equals(plan.getId(), planId));
    }

    private void validateBranding(DiscountRule rule, RearWindowBranding requestedBranding) {
        normalizedBranding(rule, requestedBranding);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private Set<String> normalizeCodes(Collection<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .filter(Objects::nonNull)
                .map(this::normalizeCode)
                .filter(code -> !code.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private String currency(BigDecimal amount) {
        return java.text.NumberFormat.getCurrencyInstance(new Locale("pt", "BR")).format(amount);
    }

    private String coverageAmountLabel(BigDecimal amount) {
        if (amount == null) return "—";
        BigDecimal thousand = BigDecimal.valueOf(1000);
        BigDecimal[] division = amount.divideAndRemainder(thousand);
        if (division[1].compareTo(BigDecimal.ZERO) == 0) {
            return "R$ " + division[0].stripTrailingZeros().toPlainString() + " mil";
        }
        return currency(amount);
    }
}
