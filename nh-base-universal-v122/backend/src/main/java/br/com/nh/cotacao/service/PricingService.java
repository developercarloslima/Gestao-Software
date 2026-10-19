package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.Plan;
import br.com.nh.cotacao.entity.PricingRegion;
import br.com.nh.cotacao.entity.PlanRegionalPricingRule;
import br.com.nh.cotacao.repository.PlanRepository;
import br.com.nh.cotacao.repository.PlanRegionalPricingRuleRepository;
import br.com.nh.cotacao.repository.PriceRangeRepository;
import br.com.nh.cotacao.repository.PromotionalMotorcyclePriceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PricingService {

    private static final String ECONOMIC_CAR_PLAN_CODE = "CAR_ECONOMICO";
    private static final String NATIONAL_CAR_CATEGORY_CODE = "CAR_NATIONAL";
    private static final BigDecimal ROBBERY_THEFT_FACTOR = new BigDecimal("0.90");

    private final PriceRangeRepository priceRangeRepository;
    private final PromotionalMotorcyclePriceRepository promotionalMotorcyclePriceRepository;
    private final PlanRepository planRepository;
    private final PlanRegionalPricingRuleRepository regionalPricingRuleRepository;

    public PricingService(
            PriceRangeRepository priceRangeRepository,
            PromotionalMotorcyclePriceRepository promotionalMotorcyclePriceRepository,
            PlanRepository planRepository,
            PlanRegionalPricingRuleRepository regionalPricingRuleRepository
    ) {
        this.priceRangeRepository = priceRangeRepository;
        this.promotionalMotorcyclePriceRepository = promotionalMotorcyclePriceRepository;
        this.planRepository = planRepository;
        this.regionalPricingRuleRepository = regionalPricingRuleRepository;
    }

    public record PromotionalMotorcyclePriceView(
            String tierCode,
            String label,
            Integer minCc,
            Integer maxCc,
            BigDecimal minFipe,
            BigDecimal maxFipe,
            BigDecimal monthlyPrice
    ) {}

    public record PricingResult(
            BigDecimal tableMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription
    ) {
        public BigDecimal totalMonthlyValue() {
            return tableMonthlyValue.add(mandatoryMonthlyFee).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public List<PromotionalMotorcyclePriceView> promotionalMotorcyclePrices() {
        return promotionalMotorcyclePriceRepository.findAllByOrderBySortOrderAsc().stream()
                .map(item -> new PromotionalMotorcyclePriceView(
                        item.getTierCode(), item.getLabel(), item.getMinCc(), item.getMaxCc(), item.getMinFipe(), item.getMaxFipe(),
                        item.getMonthlyPrice().setScale(2, RoundingMode.HALF_UP)
                ))
                .toList();
    }

    public Optional<String> findMatchingPromotionalTierCode(BigDecimal fipeValue, Integer motorcycleCc) {
        if (fipeValue == null || fipeValue.signum() <= 0 || motorcycleCc == null || motorcycleCc < 1) {
            return Optional.empty();
        }
        return promotionalMotorcyclePriceRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(item -> item.matches(fipeValue, motorcycleCc))
                .map(item -> item.getTierCode())
                .findFirst();
    }

    public Optional<BigDecimal> calculate(Plan plan, BigDecimal fipeValue) {
        return calculate(plan, fipeValue, PricingRegion.NORTHEAST);
    }

    public Optional<BigDecimal> calculate(Plan plan, BigDecimal fipeValue, PricingRegion pricingRegion) {
        return calculateBreakdown(plan, fipeValue, pricingRegion).map(PricingResult::totalMonthlyValue);
    }

    public Optional<PricingResult> calculateBreakdown(Plan plan, BigDecimal fipeValue) {
        return calculateBreakdown(plan, fipeValue, PricingRegion.NORTHEAST);
    }

    public Optional<PricingResult> calculateBreakdown(Plan plan, BigDecimal fipeValue, PricingRegion pricingRegion) {
        return calculateStandardBreakdown(plan, fipeValue, effectiveRegion(pricingRegion));
    }

    /**
     * Regra comercial do plano "Roubo e Furto Carros Nacionais":
     * a mensalidade final deve ser sempre exatamente 10% menor que a mensalidade
     * final ofertada pelo Plano Econômico para a mesma FIPE.
     *
     * A regra independe de faixa própria cadastrada no plano de roubo/furto.
     * Assim, sempre que o Plano Econômico possuir valor para a FIPE informada,
     * o plano de roubo/furto também poderá ser ofertado.
     */
    private Optional<PricingResult> calculateNationalCarRobberyTheft(Plan robberyTheftPlan, BigDecimal fipeValue) {
        if (fipeValue == null || fipeValue.signum() <= 0) {
            return Optional.empty();
        }

        Optional<Plan> economicPlan = planRepository.findAvailableByCode(ECONOMIC_CAR_PLAN_CODE)
                .filter(plan -> plan.getCategory() != null
                        && NATIONAL_CAR_CATEGORY_CODE.equalsIgnoreCase(plan.getCategory().getCode()));
        if (economicPlan.isEmpty()) {
            return Optional.empty();
        }

        Optional<PricingResult> economicPricing = calculateStandardBreakdown(economicPlan.get(), fipeValue, PricingRegion.NORTHEAST);
        if (economicPricing.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal targetMonthly = economicPricing.get().totalMonthlyValue()
                .multiply(ROBBERY_THEFT_FACTOR)
                .setScale(2, RoundingMode.HALF_UP);

        boolean trackerRequired = robberyTheftPlan.getTrackerRequiredAbove() != null
                && fipeValue.compareTo(robberyTheftPlan.getTrackerRequiredAbove()) >= 0;

        BigDecimal configuredTrackerMonthly = trackerRequired && robberyTheftPlan.getTrackerMonthlyFee() != null
                ? robberyTheftPlan.getTrackerMonthlyFee().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        // O rastreador, quando obrigatório, compõe o valor final promocional.
        // Nunca é somado por fora, pois isso quebraria a regra de "10% mais barato".
        BigDecimal mandatoryMonthlyFee = configuredTrackerMonthly.min(targetMonthly);
        BigDecimal tableMonthlyValue = targetMonthly.subtract(mandatoryMonthlyFee)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal oneTimeFee = trackerRequired && robberyTheftPlan.getTrackerInstallationFee() != null
                ? robberyTheftPlan.getTrackerInstallationFee().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        String description = trackerRequired
                ? "Rastreador obrigatório. A mensalidade do rastreador já está incluída no valor final, que é 10% menor que o Plano Econômico."
                : "Valor mensal calculado automaticamente em 90% do Plano Econômico para a mesma FIPE.";

        return Optional.of(new PricingResult(
                tableMonthlyValue,
                mandatoryMonthlyFee,
                oneTimeFee,
                description
        ));
    }

    private Optional<PricingResult> calculateStandardBreakdown(Plan plan, BigDecimal fipeValue, PricingRegion pricingRegion) {
        Optional<BigDecimal> tableValue = findTableValue(plan, fipeValue, pricingRegion);
        if (tableValue.isEmpty()) {
            return Optional.empty();
        }

        boolean trackerRequired = plan.getTrackerRequiredAbove() != null
                && fipeValue.compareTo(plan.getTrackerRequiredAbove()) >= 0;

        BigDecimal mandatoryMonthlyFee = trackerRequired && plan.getTrackerMonthlyFee() != null
                ? plan.getTrackerMonthlyFee()
                : BigDecimal.ZERO;
        BigDecimal oneTimeFee = trackerRequired && plan.getTrackerInstallationFee() != null
                ? plan.getTrackerInstallationFee()
                : BigDecimal.ZERO;
        String description = trackerRequired
                ? "Rastreador obrigatório para veículos com FIPE a partir de " + formatCurrency(plan.getTrackerRequiredAbove())
                : null;

        return Optional.of(new PricingResult(
                tableValue.get().setScale(2, RoundingMode.HALF_UP),
                mandatoryMonthlyFee.setScale(2, RoundingMode.HALF_UP),
                oneTimeFee.setScale(2, RoundingMode.HALF_UP),
                description
        ));
    }

    private boolean isNationalCarRobberyTheftPlan(Plan plan) {
        if (plan == null || plan.getName() == null || plan.getCategory() == null
                || !NATIONAL_CAR_CATEGORY_CODE.equalsIgnoreCase(plan.getCategory().getCode())) {
            return false;
        }
        String normalizedName = java.text.Normalizer.normalize(plan.getName(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return normalizedName.contains("roubo") && normalizedName.contains("furto");
    }

    /**
     * Regra promocional de motocicletas. A tabela só é considerada quando o
     * plano MOTO_PROMO_2026 está ativo no catálogo administrativo.
     * As faixas, limites de cilindrada/FIPE e mensalidades são mantidos na aba Valores do painel administrativo.
     * A faixa escolhida na cotação é validada contra as condições atualmente salvas pelo Admin.
     */
    public Optional<PricingResult> calculatePromotionalMotorcycle(
            Plan plan, BigDecimal fipeValue, Integer motorcycleCc, String tierCode
    ) {
        if (plan == null || !"MOTO_PROMO_2026".equals(plan.getCode()) || !Boolean.TRUE.equals(plan.getActive())) {
            return Optional.empty();
        }
        if (fipeValue == null || fipeValue.signum() <= 0 || motorcycleCc == null || motorcycleCc < 1 || motorcycleCc > 2500) {
            return Optional.empty();
        }
        if (tierCode == null || tierCode.isBlank()) return Optional.empty();

        return promotionalMotorcyclePriceRepository.findByTierCode(tierCode.trim())
                .filter(item -> item.matches(fipeValue, motorcycleCc))
                .map(item -> new PricingResult(
                        item.getMonthlyPrice().setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        null
                ));
    }

    private String formatCurrency(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(value);
    }

    private Optional<BigDecimal> findTableValue(Plan plan, BigDecimal fipeValue, PricingRegion pricingRegion) {
        PricingRegion effectiveRegion = effectiveRegion(pricingRegion);
        var matching = priceRangeRepository
                .findFirstByPlanIdAndPricingRegionAndMinValueLessThanEqualAndMaxValueGreaterThanEqualOrderByMinValueAsc(
                        plan.getId(), effectiveRegion, fipeValue, fipeValue
                );

        if (matching.isPresent()) {
            return Optional.of(matching.get().getMonthlyPrice());
        }

        Optional<PlanRegionalPricingRule> regionalRule = regionalPricingRuleRepository
                .findByPlan_IdAndPricingRegion(plan.getId(), effectiveRegion);
        if (regionalRule.isPresent()) {
            PlanRegionalPricingRule rule = regionalRule.get();
            if (rule.getExtraAbove() == null || rule.getExtraStep() == null || rule.getExtraIncrement() == null
                    || rule.getExtraBasePrice() == null || fipeValue.compareTo(rule.getExtraAbove()) <= 0) {
                return Optional.empty();
            }
            BigDecimal difference = fipeValue.subtract(rule.getExtraAbove());
            BigDecimal steps = difference.divide(rule.getExtraStep(), 0, RoundingMode.CEILING);
            return Optional.of(rule.getExtraBasePrice().add(rule.getExtraIncrement().multiply(steps))
                    .setScale(2, RoundingMode.HALF_UP));
        }

        // Compatibilidade com planos legados que ainda não possuem regra regional.
        if (plan.getExtraAbove() == null
                || plan.getExtraStep() == null
                || plan.getExtraIncrement() == null
                || plan.getExtraBasePrice() == null
                || fipeValue.compareTo(plan.getExtraAbove()) <= 0) {
            return Optional.empty();
        }

        BigDecimal difference = fipeValue.subtract(plan.getExtraAbove());
        BigDecimal steps = difference.divide(plan.getExtraStep(), 0, RoundingMode.CEILING);
        BigDecimal price = plan.getExtraBasePrice().add(plan.getExtraIncrement().multiply(steps));
        return Optional.of(price.setScale(2, RoundingMode.HALF_UP));
    }

    private PricingRegion effectiveRegion(PricingRegion pricingRegion) {
        return pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
    }
}
