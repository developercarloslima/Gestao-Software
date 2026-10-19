package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.CoverageStatus;
import br.com.nh.cotacao.entity.DiscountRule;
import br.com.nh.cotacao.entity.RearWindowBranding;
import br.com.nh.cotacao.entity.Plan;
import br.com.nh.cotacao.entity.PlanCoverage;
import br.com.nh.cotacao.entity.Quotation;
import br.com.nh.cotacao.repository.PlanRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reconstrói a mensalidade comercial exclusivamente a partir do catálogo
 * vigente: plano + tipo/faixa FIPE + taxas obrigatórias realmente aplicáveis +
 * serviços opcionais atualmente selecionados - desconto.
 *
 * Nenhum valor mensal histórico da cotação é reutilizado como base. Isso é
 * importante principalmente em revisões de contratos antigos que carregavam
 * rastreador ou adicionais que já não fazem parte da composição atual.
 */
@Service
public class CommercialPricingService {

    private final PlanRepository planRepository;
    private final PricingService pricingService;
    private final DiscountRuleService discountRuleService;

    public CommercialPricingService(
            PlanRepository planRepository, PricingService pricingService, DiscountRuleService discountRuleService
    ) {
        this.planRepository = planRepository;
        this.pricingService = pricingService;
        this.discountRuleService = discountRuleService;
    }

    public record Breakdown(
            BigDecimal planBaseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription,
            BigDecimal optionalsMonthlyValue,
            BigDecimal subtotalBeforeDiscount,
            BigDecimal discountValue,
            BigDecimal finalMonthlyValue,
            boolean catalogBased
    ) {}

    /** Item do catálogo atual exibido na segunda etapa da correção comercial. */
    public record BenefitCatalogItem(
            String code,
            String name,
            CoverageStatus status,
            String detail,
            BigDecimal monthlyPrice,
            Integer sortOrder
    ) {}

    /** Serviço opcional calculado pelo valor que está cadastrado hoje no plano. */
    public record OptionalPricingItem(
            String code,
            String name,
            String detail,
            BigDecimal catalogMonthlyPrice,
            BigDecimal discountedMonthlyPrice
    ) {}

    public Breakdown calculate(Quotation quotation, BigDecimal requestedFipe, int discountPercent, Set<String> requestedBenefits) {
        if (quotation == null) {
            throw new IllegalArgumentException("Esta vistoria não possui cotação vinculada.");
        }
        if (requestedFipe == null || requestedFipe.signum() <= 0) {
            throw new IllegalArgumentException("Informe um valor FIPE válido.");
        }
        Plan plan = requireCurrentPlan(quotation);
        DiscountRule discountRule = discountRuleService.requireApplicable(
                plan, discountPercent, requestedFipe, quotation.getManufactureYear(), RearWindowBranding.ANY_PERFORATED
        );
        PricingService.PricingResult pricing = calculateCatalogBreakdown(quotation, plan, requestedFipe);

        BigDecimal base = money(pricing.tableMonthlyValue());
        BigDecimal mandatory = money(pricing.mandatoryMonthlyFee());
        BigDecimal oneTime = money(pricing.oneTimeFee());
        String mandatoryDescription = pricing.mandatoryFeeDescription();

        Set<String> selected = normalizeCodes(requestedBenefits);
        BigDecimal optionals = currentPlanCoverages(plan).stream()
                .filter(item -> item.getStatus() == CoverageStatus.OPTIONAL)
                .filter(item -> item.getCoverage() != null && item.getCoverage().getCode() != null)
                .filter(item -> selected.contains(normalizeCode(item.getCoverage().getCode())))
                .map(item -> money(item.getMonthlyPrice()))
                .reduce(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        DiscountRuleService.PriceResult discountPricing = discountRuleService.calculate(
                discountRule, base, mandatory, optionals
        );
        BigDecimal subtotal = discountPricing.subtotal();
        BigDecimal discount = discountPricing.discountValue();
        BigDecimal finalValue = discountPricing.finalValue();

        return new Breakdown(
                base, mandatory, oneTime, mandatoryDescription, optionals,
                subtotal, discount, finalValue, true
        );
    }

    /**
     * Lista os benefícios/serviços da configuração atual do plano. Assim um
     * adicional criado ou corrigido no Admin também aparece na etapa de correção
     * de vistorias antigas, em vez de depender do snapshot histórico da cotação.
     */
    public List<BenefitCatalogItem> benefitCatalog(Quotation quotation) {
        Plan plan = requireCurrentPlan(quotation);
        return currentPlanCoverages(plan).stream()
                .filter(item -> isBenefitAllowedForPlan(plan, item))
                .map(item -> new BenefitCatalogItem(
                        item.getCoverage().getCode(),
                        item.getCoverage().getName(),
                        item.getStatus(),
                        item.getEffectiveDetail(),
                        item.getStatus() == CoverageStatus.OPTIONAL ? money(item.getMonthlyPrice()) : null,
                        item.getSortOrder()
                ))
                .toList();
    }

    public Set<String> benefitCodes(Quotation quotation) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        benefitCatalog(quotation).stream()
                .map(BenefitCatalogItem::code)
                .map(this::normalizeCode)
                .filter(code -> !code.isBlank())
                .forEach(codes::add);
        return codes;
    }

    public Set<String> filterToCurrentCatalog(Quotation quotation, Set<String> requestedCodes) {
        Set<String> allowed = benefitCodes(quotation);
        LinkedHashSet<String> filtered = new LinkedHashSet<>();
        if (requestedCodes == null) return filtered;
        requestedCodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::normalizeCode)
                .filter(code -> !code.isBlank() && allowed.contains(code))
                .forEach(filtered::add);
        return filtered;
    }

    /**
     * Atualiza a lista persistida de adicionais com os preços atuais do catálogo
     * antes de consolidar o novo valor no contrato.
     */
    public void synchronizeSelectedOptionals(Quotation quotation, Set<String> selectedBenefits) {
        Plan plan = requireCurrentPlan(quotation);
        quotation.synchronizeSelectedOptionalsFromCatalog(currentPlanCoverages(plan), normalizeCodes(selectedBenefits));
    }

    /**
     * Retorna os opcionais atuais e seus valores já considerando o desconto.
     * Usado também na página em que o associado confirma uma revisão de valor.
     */
    public List<OptionalPricingItem> optionalPricingItems(
            Quotation quotation, Set<String> selectedBenefits, int discountPercent
    ) {
        return optionalPricingItems(quotation, quotation.getFipeValue(), selectedBenefits, discountPercent);
    }

    /**
     * Variante usada em revisões comerciais pendentes. A elegibilidade do desconto
     * precisa ser validada contra a FIPE proposta, e não contra a FIPE antiga que
     * ainda está gravada na cotação até o associado confirmar a alteração.
     */
    public List<OptionalPricingItem> optionalPricingItems(
            Quotation quotation, BigDecimal requestedFipe, Set<String> selectedBenefits, int discountPercent
    ) {
        Plan plan = requireCurrentPlan(quotation);
        DiscountRule discountRule = discountRuleService.requireApplicable(
                plan, discountPercent, requestedFipe, quotation.getManufactureYear(), RearWindowBranding.ANY_PERFORATED
        );
        Set<String> selected = normalizeCodes(selectedBenefits);
        boolean discountOptionals = discountRule != null && Boolean.TRUE.equals(discountRule.getApplyToOptionals());
        BigDecimal factor = discountOptionals
                ? BigDecimal.valueOf(100 - discountPercent).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        return currentPlanCoverages(plan).stream()
                .filter(item -> item.getStatus() == CoverageStatus.OPTIONAL)
                .filter(item -> item.getCoverage() != null && item.getCoverage().getCode() != null)
                .filter(item -> selected.contains(normalizeCode(item.getCoverage().getCode())))
                .map(item -> {
                    BigDecimal catalogPrice = money(item.getMonthlyPrice());
                    return new OptionalPricingItem(
                            item.getCoverage().getCode(),
                            item.getCoverage().getName(),
                            item.getEffectiveDetail(),
                            catalogPrice,
                            catalogPrice.multiply(factor).setScale(2, RoundingMode.HALF_UP)
                    );
                })
                .toList();
    }

    private PricingService.PricingResult calculateCatalogBreakdown(Quotation quotation, Plan plan, BigDecimal requestedFipe) {
        java.util.Optional<PricingService.PricingResult> catalogPricing;
        if ("MOTO_PROMO_2026".equalsIgnoreCase(plan.getCode())) {
            String tierCode = pricingService.findMatchingPromotionalTierCode(requestedFipe, quotation.getMotorcycleCc())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "A FIPE/cilindrada informada não se enquadra na tabela promocional de motocicletas."
                    ));
            catalogPricing = pricingService.calculatePromotionalMotorcycle(
                    plan, requestedFipe, quotation.getMotorcycleCc(), tierCode
            );
        } else {
            // calculateBreakdown consulta as faixas cadastradas (ou a regra extra
            // acima da última faixa) e calcula o rastreador somente quando os
            // campos atuais do plano dizem que ele é obrigatório para esta FIPE.
            catalogPricing = pricingService.calculateBreakdown(plan, requestedFipe);
        }
        return catalogPricing.orElseThrow(() -> new IllegalArgumentException(
                "Não existe faixa de preço cadastrada para este plano e a FIPE informada."
        ));
    }

    private Plan requireCurrentPlan(Quotation quotation) {
        String planCode = quotation.getSelectedPlanCode();
        if (planCode == null || planCode.isBlank()) {
            throw new IllegalArgumentException("A cotação não possui o código do plano necessário para recalcular a mensalidade.");
        }
        return planRepository.findByCode(planCode.trim())
                .orElseThrow(() -> new IllegalArgumentException("O plano da cotação não foi encontrado no catálogo atual."));
    }

    private boolean isBenefitAllowedForPlan(Plan plan, PlanCoverage item) {
        if (plan == null || item == null || item.getCoverage() == null || item.getCoverage().getCode() == null) return false;
        String code = normalizeCode(item.getCoverage().getCode());
        String categoryCode = plan.getCategory() == null ? "" : plan.getCategory().getCode();
        if (item.getStatus() == CoverageStatus.OPTIONAL
                && !item.getCoverage().allowsOptionalForVehicleCategory(categoryCode)) {
            return false;
        }
        if (!Quotation.isDiscountExcludedBenefit(code)) return true;
        String planCode = plan.getCode() == null ? "" : plan.getCode().trim().toUpperCase(Locale.ROOT);
        return ("CAR_COMPLETO".equals(planCode) || "UTILITY_COMPLETO".equals(planCode))
                && item.getStatus() == CoverageStatus.INCLUDED;
    }

    private List<PlanCoverage> currentPlanCoverages(Plan plan) {
        if (plan == null || plan.getCoverages() == null) return List.of();
        return plan.getCoverages().stream()
                .filter(item -> isBenefitAllowedForPlan(plan, item))
                .toList();
    }

    private Set<String> normalizeCodes(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::normalizeCode)
                .filter(code -> !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
