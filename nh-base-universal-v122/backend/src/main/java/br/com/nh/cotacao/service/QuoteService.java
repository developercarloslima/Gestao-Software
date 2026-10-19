package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;

import br.com.nh.cotacao.dto.InspectionDtos.InspectionResponse;
import br.com.nh.cotacao.dto.QuoteDtos.*;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.CoverageRuleRepository;
import br.com.nh.cotacao.repository.PlanRepository;
import br.com.nh.cotacao.repository.QuotationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.Year;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class QuoteService {

    private final PlanRepository planRepository;
    private final QuotationRepository quotationRepository;
    private final CoverageRuleRepository coverageRuleRepository;
    private final PricingService pricingService;
    private final DiscountRuleService discountRuleService;
    private final ThirdPartyRuleSettingsService thirdPartyRuleSettingsService;
    private final CommercialFinanceService commercialFinanceService;
    private final ConsultantService consultantService;
    private final RetratoService retratoService;
    private final String publicApiUrl;
    private final CommunicationSettingsService communicationSettings;
    private final PublicQuoteAssignmentSettingsService publicQuoteAssignmentSettings;

    public QuoteService(
            PlanRepository planRepository,
            QuotationRepository quotationRepository,
            CoverageRuleRepository coverageRuleRepository,
            PricingService pricingService,
            DiscountRuleService discountRuleService,
            ThirdPartyRuleSettingsService thirdPartyRuleSettingsService,
            CommercialFinanceService commercialFinanceService,
            ConsultantService consultantService,
            RetratoService retratoService,
            CommunicationSettingsService communicationSettings,
            PublicQuoteAssignmentSettingsService publicQuoteAssignmentSettings,
            @Value("${app.public-api-url:http://localhost:8080}") String publicApiUrl
    ) {
        this.planRepository = planRepository;
        this.quotationRepository = quotationRepository;
        this.coverageRuleRepository = coverageRuleRepository;
        this.pricingService = pricingService;
        this.discountRuleService = discountRuleService;
        this.thirdPartyRuleSettingsService = thirdPartyRuleSettingsService;
        this.commercialFinanceService = commercialFinanceService;
        this.consultantService = consultantService;
        this.retratoService = retratoService;
        this.publicApiUrl = stripTrailingSlash(publicApiUrl);
        this.communicationSettings = communicationSettings;
        this.publicQuoteAssignmentSettings = publicQuoteAssignmentSettings;
    }

    @Transactional(readOnly = true)
    public OptionsResponse options(OptionsRequest request) {
        PricingRegion pricingRegion = request.effectivePricingRegion();
        MotorcycleOrigin motorcycleOrigin = validateMotorcycleOrigin(
                request.categoryCode(), request.effectiveMotorcycleOrigin()
        );
        Integer motorcycleCc = validateMotorcycleSelection(
                request.categoryCode(), request.motorcycle(), request.motorcycleCc()
        );
        String promoMotorcycleTier = validatePromotionalMotorcycleTier(request.categoryCode(), request.promoMotorcycleTier());
        List<PlanOption> options = "MOTORCYCLE_PROMO_2026".equals(request.categoryCode())
                ? (pricingRegion == PricingRegion.NORTHEAST
                    ? promotionalMotorcycleOption(request.fipeValue(), motorcycleCc, promoMotorcycleTier, request.manufactureYear()).map(List::of).orElseGet(List::of)
                    : List.of())
                : planRepository
                        .findAvailable(request.categoryCode(), Region.NATIONAL, motorcycleOrigin)
                        .stream()
                        .map(plan -> toPlanOption(plan, request.fipeValue(), request.manufactureYear(), pricingRegion))
                        .flatMap(java.util.Optional::stream)
                        .toList();

        if (options.isEmpty()) {
            if ("SCOOTER_ELECTRIC".equalsIgnoreCase(request.categoryCode())) {
                throw new IllegalArgumentException(
                        "O valor FIPE informado está fora da tabela cadastrada para scooters e motos elétricas."
                );
            }
            throw new IllegalArgumentException("Nenhum plano possui faixa de preço para os dados informados.");
        }

        return new OptionsResponse(
                request.categoryCode(), Region.NATIONAL, pricingRegion, motorcycleOrigin, request.fipeValue(), options
        );
    }

    @Transactional
    public QuoteResponse create(CreateQuoteRequest request) {
        validateYear(request.manufactureYear());
        PricingRegion pricingRegion = request.effectivePricingRegion();
        String cpf = normalizeOptionalCpf(request.cpf());
        String plate = validateAndNormalizePlate(request.plate(), request.zeroKm());
        MotorcycleOrigin motorcycleOrigin = validateMotorcycleOrigin(
                request.categoryCode(), request.effectiveMotorcycleOrigin()
        );
        Integer motorcycleCc = validateMotorcycleSelection(
                request.categoryCode(), request.motorcycle(), request.motorcycleCc()
        );
        String promoMotorcycleTier = validatePromotionalMotorcycleTier(request.categoryCode(), request.promoMotorcycleTier());
        PlanSelection selection = resolvePlan(
                request.selectedPlanCode(), request.categoryCode(), pricingRegion, motorcycleOrigin, motorcycleCc, promoMotorcycleTier,
                request.fipeValue(), request.selectedOptionalCodes()
        );
        Consultant consultant = consultantService.findActive(request.consultantId());

        Quotation quotation = Quotation.createForConsultant(
                buildQuoteNumber(),
                consultant,
                request.customerName(),
                cpf,
                normalizePhone(request.whatsapp()),
                plate,
                request.model(),
                request.manufactureYear(),
                request.zeroKm(),
                request.fipeValue(),
                request.categoryCode(),
                Region.NATIONAL,
                pricingRegion,
                motorcycleOrigin,
                motorcycleCc,
                request.observation(),
                selection.plan().getCode(),
                selection.plan().getName(),
                selection.pricing().tableMonthlyValue(),
                selection.pricing().mandatoryMonthlyFee(),
                selection.pricing().oneTimeFee(),
                selection.pricing().mandatoryFeeDescription()
        );
        quotation.configureResidenceAddress(request.residenceCep(), request.residenceAddress());
        quotation.configureVehicleHistory(request.auctionOrChassisRemarked());
        quotation.configureBillingDueDate(request.firstBillingDueDate());

        DiscountRule discountRule = discountRuleService.requireApplicable(
                selection.plan(), request.discountPercent(), request.fipeValue(), request.manufactureYear(), request.rearWindowBranding()
        );
        RearWindowBranding branding = discountRuleService.normalizedBranding(discountRule, request.rearWindowBranding());
        Set<String> selectedBenefits = discountRuleService.applyBenefitRules(
                selection.plan(), defaultSelectedBenefitCodes(selection.plan(), selection.optionals()), discountRule
        );
        List<PlanCoverage> selectedOptionals = selectedOptionalCoveragesForBenefits(selection.plan(), selectedBenefits);
        applyCatalogSnapshot(quotation, selection.plan(), selectedOptionals, request.discountPercent(), discountRule, selectedBenefits);
        BigDecimal optionalsTotal = selectedOptionals.stream()
                .map(PlanCoverage::getMonthlyPrice).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var discountPricing = discountRuleService.calculate(
                discountRule, selection.pricing().tableMonthlyValue(), selection.pricing().mandatoryMonthlyFee(), optionalsTotal
        );
        quotation.applyDiscountPricing(request.discountPercent(), branding, discountPricing.finalValue());
        Quotation saved = quotationRepository.saveAndFlush(quotation);
        commercialFinanceService.registerQuotation(saved, request.associateId(), request.adhesionPaymentMethod());
        return toResponse(saved);
    }

    @Transactional
    public QuoteResponse createPublic(CreatePublicQuoteRequest request) {
        validateYear(request.manufactureYear());
        PricingRegion pricingRegion = request.effectivePricingRegion();
        String cpf = normalizeOptionalCpf(request.cpf());

        String whatsapp = normalizePhone(request.whatsapp());
        if (whatsapp == null || whatsapp.length() < 10 || whatsapp.length() > 13) {
            throw new IllegalArgumentException("Informe um WhatsApp válido com DDD.");
        }
        String plate = validateAndNormalizePlate(request.plate(), request.zeroKm());

        MotorcycleOrigin motorcycleOrigin = validateMotorcycleOrigin(
                request.categoryCode(), request.effectiveMotorcycleOrigin()
        );
        Integer motorcycleCc = validateMotorcycleSelection(
                request.categoryCode(), request.motorcycle(), request.motorcycleCc()
        );
        String promoMotorcycleTier = validatePromotionalMotorcycleTier(request.categoryCode(), request.promoMotorcycleTier());
        PlanSelection selection = resolvePlan(
                request.selectedPlanCode(), request.categoryCode(), pricingRegion, motorcycleOrigin, motorcycleCc, promoMotorcycleTier,
                request.fipeValue(), request.selectedOptionalCodes()
        );

        Consultant assignedConsultant = publicQuoteAssignmentSettings.assignToLastLoggedConsultant()
                ? consultantService.findMostRecentPortalConsultant().orElse(null)
                : null;

        Quotation quotation = Quotation.createSelfService(
                buildQuoteNumber(),
                assignedConsultant,
                request.customerName(),
                cpf,
                whatsapp,
                plate,
                request.model(),
                request.manufactureYear(),
                request.zeroKm(),
                request.fipeValue(),
                request.categoryCode(),
                Region.NATIONAL,
                pricingRegion,
                motorcycleOrigin,
                motorcycleCc,
                request.observation(),
                selection.plan().getCode(),
                selection.plan().getName(),
                selection.pricing().tableMonthlyValue(),
                selection.pricing().mandatoryMonthlyFee(),
                selection.pricing().oneTimeFee(),
                selection.pricing().mandatoryFeeDescription()
        );
        quotation.configureResidenceAddress(request.residenceCep(), request.residenceAddress());
        quotation.configureVehicleHistory(request.auctionOrChassisRemarked());
        quotation.configureBillingDueDate(request.firstBillingDueDate());
        Set<String> selectedBenefits = defaultSelectedBenefitCodes(selection.plan(), selection.optionals());
        applyCatalogSnapshot(quotation, selection.plan(), selection.optionals(), 0, null, selectedBenefits);
        Quotation saved = quotationRepository.saveAndFlush(quotation);
        commercialFinanceService.registerQuotation(saved, null, br.com.nh.cotacao.entity.AdhesionPaymentMethod.BOLETO);
        return toResponse(saved);
    }

    /**
     * Gera uma nova cotação a partir de uma cotação vencida, recalculando o plano e os
     * opcionais com a tabela atualmente ativa. A cotação anterior é preservada no histórico.
     */
    @Transactional
    public Quotation recreateForConsultant(Quotation source, Consultant consultant, String requestedCpf) {
        if (source == null) throw new IllegalArgumentException("Cotação não encontrada.");
        if (consultant == null) throw new IllegalArgumentException("Informe o consultor responsável.");

        boolean expired = source.getStatus() != QuoteStatus.CANCELLED
                && OffsetDateTime.now().isAfter(source.getValidUntil())
                && !retratoService.hasPreservedFileForQuotation(source.getId());
        if (!expired) {
            throw new IllegalArgumentException("Somente uma cotação vencida pode ser refeita.");
        }

        String cpf = hasValidCpf(source.getCustomerCpf())
                ? normalizeCpf(source.getCustomerCpf())
                : normalizeOptionalCpf(requestedCpf);

        List<String> optionalCodes = source.getSelectedOptionals().stream()
                .map(QuotationOptionalCoverage::getCoverageCode)
                .toList();
        PlanSelection selection = resolvePlan(
                source.getSelectedPlanCode(),
                source.getCategoryCode(),
                source.getPricingRegion(),
                source.getMotorcycleOrigin(),
                source.getMotorcycleCc(),
                source.getFipeValue(),
                optionalCodes
        );

        Quotation recreated = Quotation.createForConsultant(
                buildQuoteNumber(),
                consultant,
                source.getCustomerName(),
                cpf,
                normalizePhone(source.getWhatsapp()),
                validateAndNormalizePlate(source.getPlate(), source.isZeroKm()),
                source.getModel(),
                source.getManufactureYear(),
                source.isZeroKm(),
                source.getFipeValue(),
                source.getCategoryCode(),
                Region.NATIONAL,
                source.getPricingRegion(),
                source.getMotorcycleOrigin(),
                source.getMotorcycleCc(),
                source.getObservation(),
                selection.plan().getCode(),
                selection.plan().getName(),
                selection.pricing().tableMonthlyValue(),
                selection.pricing().mandatoryMonthlyFee(),
                selection.pricing().oneTimeFee(),
                selection.pricing().mandatoryFeeDescription()
        );
        recreated.configureResidenceAddress(source.getResidenceCep(), source.getResidenceAddress());
        recreated.configureVehicleHistory(source.getAuctionOrChassisRemarked());
        recreated.configureBillingDueDate(resolveBillingDueDateForRecreatedQuote(recreated, source.getBillingDueDay()));
        DiscountRule discountRule = discountRuleService.requireApplicable(
                selection.plan(), source.getDiscountPercent(), source.getFipeValue(), source.getManufactureYear(), source.getRearWindowBranding()
        );
        RearWindowBranding branding = discountRuleService.normalizedBranding(discountRule, source.getRearWindowBranding());
        Set<String> selectedBenefits = discountRuleService.applyBenefitRules(
                selection.plan(), defaultSelectedBenefitCodes(selection.plan(), selection.optionals()), discountRule
        );
        List<PlanCoverage> selectedOptionals = selectedOptionalCoveragesForBenefits(selection.plan(), selectedBenefits);
        applyCatalogSnapshot(recreated, selection.plan(), selectedOptionals, source.getDiscountPercent(), discountRule, selectedBenefits);
        BigDecimal optionalsTotal = selectedOptionals.stream()
                .map(PlanCoverage::getMonthlyPrice).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var discountPricing = discountRuleService.calculate(
                discountRule, selection.pricing().tableMonthlyValue(), selection.pricing().mandatoryMonthlyFee(), optionalsTotal
        );
        recreated.applyDiscountPricing(source.getDiscountPercent(), branding, discountPricing.finalValue());
        return quotationRepository.save(recreated);
    }

    private LocalDate resolveBillingDueDateForRecreatedQuote(Quotation quotation, Integer preferredDay) {
        LocalDate quoteDate = quotation.getCreatedAt()
                .atZoneSameInstant(java.time.ZoneId.of("America/Maceio"))
                .toLocalDate();
        LocalDate minimum = quoteDate.plusDays(30);
        LocalDate maximum = quoteDate.plusDays(40);
        java.util.Set<Integer> allowed = java.util.Set.of(5, 10, 15, 20, 25, 30);
        java.util.List<LocalDate> options = minimum.datesUntil(maximum.plusDays(1))
                .filter(date -> allowed.contains(date.getDayOfMonth()))
                .toList();
        if (options.isEmpty()) {
            throw new IllegalStateException("Não foi possível calcular um vencimento entre 30 e 40 dias para a nova cotação.");
        }
        if (preferredDay != null) {
            var sameDay = options.stream().filter(date -> date.getDayOfMonth() == preferredDay).findFirst();
            if (sameDay.isPresent()) return sameDay.get();
        }
        return options.get(options.size() - 1);
    }

    public boolean hasValidCustomerCpf(Quotation quotation) {
        return quotation != null && hasValidCpf(quotation.getCustomerCpf());
    }

    @Transactional
    public String ensureCustomerCpf(Quotation quotation, String requestedCpf) {
        if (quotation == null) throw new IllegalArgumentException("Cotação não encontrada.");
        if (hasValidCpf(quotation.getCustomerCpf())) return normalizeCpf(quotation.getCustomerCpf());

        String cpf = normalizeAndValidateCpf(requestedCpf);
        quotation.registerCustomerCpf(cpf);
        quotationRepository.flush();
        return cpf;
    }

    /**
     * Altera apenas dados cadastrais. Nenhuma informação utilizada pelo cálculo
     * de preço é recebida ou modificada por este método.
     */
    @Transactional
    public void updateNonPricingData(
            Quotation quotation,
            String customerName,
            String requestedCpf,
            String requestedWhatsapp,
            String requestedPlate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            String observation
    ) {
        if (quotation == null) throw new IllegalArgumentException("Cotação não encontrada.");
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("Informe o nome do associado.");
        }

        String cpf = requestedCpf == null || requestedCpf.isBlank()
                ? quotation.getCustomerCpf()
                : normalizeAndValidateCpf(requestedCpf);
        String whatsapp = normalizePhone(requestedWhatsapp);
        if (whatsapp != null && (whatsapp.length() < 10 || whatsapp.length() > 13)) {
            throw new IllegalArgumentException("Informe um WhatsApp válido com DDD.");
        }
        validateYear(manufactureYear);
        String plate = validateAndNormalizePlate(requestedPlate, zeroKm);

        quotation.updateNonPricingData(
                customerName, cpf, whatsapp, plate, model, manufactureYear, zeroKm, observation
        );
    }

    private PlanSelection resolvePlan(
            String selectedPlanCode,
            String categoryCode,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            Integer motorcycleCc,
            BigDecimal fipeValue,
            List<String> selectedOptionalCodes
    ) {
        String promoMotorcycleTier = null;
        if ("MOTORCYCLE_PROMO_2026".equals(categoryCode)) {
            promoMotorcycleTier = pricingService.findMatchingPromotionalTierCode(fipeValue, motorcycleCc)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "A moto não se enquadra nas condições atuais da tabela promocional."
                    ));
        }
        return resolvePlan(
                selectedPlanCode, categoryCode, pricingRegion, motorcycleOrigin, motorcycleCc, promoMotorcycleTier,
                fipeValue, selectedOptionalCodes
        );
    }

    private PlanSelection resolvePlan(
            String selectedPlanCode,
            String categoryCode,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            Integer motorcycleCc,
            String promoMotorcycleTier,
            BigDecimal fipeValue,
            List<String> selectedOptionalCodes
    ) {
        Plan selectedPlan = planRepository.findAvailableByCode(selectedPlanCode)
                .orElseThrow(() -> new IllegalArgumentException("Plano selecionado não encontrado."));

        boolean promotionalPlan = "MOTO_PROMO_2026".equals(selectedPlan.getCode());
        PricingService.PricingResult pricing;
        if (promotionalPlan) {
            if (pricingRegion != PricingRegion.NORTHEAST) {
                throw new IllegalArgumentException("A tabela promocional 2026 está disponível somente para a tabela Nordeste.");
            }
            if (!"MOTORCYCLE_PROMO_2026".equals(categoryCode)) {
                throw new IllegalArgumentException("A tabela promocional só pode ser usada na categoria Tabela Promocional - Motocicletas.");
            }
            pricing = pricingService.calculatePromotionalMotorcycle(selectedPlan, fipeValue, motorcycleCc, promoMotorcycleTier)
                    .orElseThrow(() -> new IllegalArgumentException("A moto não se enquadra nas condições da tabela promocional ativa."));
        } else {
            if (!selectedPlan.getCategory().getCode().equals(categoryCode)
                    || selectedPlan.getRegion() != Region.NATIONAL
                    || selectedPlan.getMotorcycleOrigin() != motorcycleOrigin) {
                throw new IllegalArgumentException("O plano selecionado não pertence à categoria ou origem da moto informada.");
            }
            pricing = pricingService.calculateBreakdown(selectedPlan, fipeValue, pricingRegion)
                    .orElseThrow(() -> new IllegalArgumentException("O valor FIPE está fora da tabela do plano selecionado."));
        }

        List<PlanCoverage> optionals = resolveSelectedOptionals(selectedPlan, selectedOptionalCodes);
        return new PlanSelection(selectedPlan, pricing, optionals);
    }

    private void applyCatalogSnapshot(
            Quotation quotation, Plan selectedPlan, List<PlanCoverage> selectedOptionals, Integer discountPercent,
            DiscountRule discountRule, Set<String> selectedBenefitCodes
    ) {
        selectedOptionals.forEach(item -> {
            String detail = coverageDetailForContext(
                    item.getCoverage(), item.getEffectiveDetail(), quotation.getCategoryCode(), quotation.getSelectedPlanCode(), quotation.getFipeValue(), discountPercent
            );
            if (!isProtectedThirdPartyRule(quotation, item.getCoverage().getCode())) {
                detail = discountRuleService.adjustedBenefitDetail(discountRule, item.getCoverage().getCode(), detail);
            }
            quotation.addOptional(
                    item.getCoverage().getCode(), item.getCoverage().getName(), detail, item.getMonthlyPrice()
            );
        });

        selectedPlan.getCoverages().stream()
                .filter(item -> isCoverageAvailableForPlan(selectedPlan, item))
                .sorted(Comparator.comparing(PlanCoverage::getSortOrder).thenComparing(PlanCoverage::getId))
                .forEach(item -> {
                    String detail = coverageDetailForContext(
                            item.getCoverage(), item.getEffectiveDetail(), quotation.getCategoryCode(), quotation.getSelectedPlanCode(), quotation.getFipeValue(), discountPercent
                    );
                    if (!isProtectedThirdPartyRule(quotation, item.getCoverage().getCode())) {
                        detail = discountRuleService.adjustedBenefitDetail(discountRule, item.getCoverage().getCode(), detail);
                    }
                    quotation.addCoverageSnapshot(
                            item.getCoverage().getCode(), item.getCoverage().getName(), item.getStatus(), detail,
                            item.getMonthlyPrice(), item.getSortOrder()
                    );
                });
        quotation.applyBenefitSelection(selectedBenefitCodes);
    }

    /** Um plano só pode ser oferecido na análise se estiver elegível para a mesma
     * categoria, origem, tabela regional, FIPE e cilindrada da cotação. */
    public List<Plan> eligiblePlansForInspection(Quotation quotation) {
        if (quotation == null) return List.of();
        return planRepository.findAvailable(quotation.getCategoryCode(), quotation.getRegion(), quotation.getMotorcycleOrigin())
                .stream().filter(plan -> {
                    if ("MOTO_PROMO_2026".equals(plan.getCode())) {
                        if (quotation.getPricingRegion() != PricingRegion.NORTHEAST) return false;
                        return pricingService.findMatchingPromotionalTierCode(quotation.getFipeValue(), quotation.getMotorcycleCc())
                                .flatMap(tier -> pricingService.calculatePromotionalMotorcycle(
                                        plan, quotation.getFipeValue(), quotation.getMotorcycleCc(), tier)).isPresent();
                    }
                    return pricingService.calculateBreakdown(plan, quotation.getFipeValue(), quotation.getPricingRegion()).isPresent();
                }).toList();
    }

    /** Reconstrói integralmente as coberturas ao mudar um plano ANTES do aceite digital.
     * Usa a mesma elegibilidade, limites por FIPE e regras de terceiros da cotação original.
     * Adicionais pagos do plano anterior não são transferidos automaticamente.
     */
    public void populateChangedPlanCoverages(Quotation quotation, Plan plan) {
        plan.getCoverages().stream()
                .filter(item -> isCoverageAvailableForPlan(plan, item))
                .sorted(Comparator.comparing(PlanCoverage::getSortOrder).thenComparing(PlanCoverage::getId))
                .forEach(item -> quotation.addCoverageSnapshot(
                        item.getCoverage().getCode(), item.getCoverage().getName(), item.getStatus(),
                        coverageDetailForContext(item.getCoverage(), item.getEffectiveDetail(),
                                quotation.getCategoryCode(), plan.getCode(), quotation.getFipeValue(), 0),
                        item.getMonthlyPrice(), item.getSortOrder()));
        quotation.applyBenefitSelection(defaultSelectedBenefitCodes(plan, List.of()));
    }

    private Set<String> defaultSelectedBenefitCodes(Plan plan, List<PlanCoverage> selectedOptionals) {
        LinkedHashSet<String> selected = plan.getCoverages().stream()
                .filter(item -> isCoverageAvailableForPlan(plan, item))
                .filter(item -> item.getStatus() == CoverageStatus.INCLUDED)
                .map(item -> item.getCoverage().getCode().trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (selectedOptionals != null) {
            selectedOptionals.stream()
                    .filter(item -> item.getCoverage() != null && item.getCoverage().getCode() != null)
                    .map(item -> item.getCoverage().getCode().trim().toUpperCase(Locale.ROOT))
                    .forEach(selected::add);
        }
        return selected;
    }

    private List<PlanCoverage> selectedOptionalCoveragesForBenefits(Plan plan, Set<String> selectedBenefitCodes) {
        Set<String> normalized = selectedBenefitCodes == null ? Set.of() : selectedBenefitCodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return plan.getCoverages().stream()
                .filter(item -> isCoverageAvailableForPlan(plan, item))
                .filter(item -> item.getStatus() == CoverageStatus.OPTIONAL)
                .filter(item -> item.getCoverage() != null && item.getCoverage().getCode() != null)
                .filter(item -> normalized.contains(item.getCoverage().getCode().trim().toUpperCase(Locale.ROOT)))
                .toList();
    }

    private String coverageDetailForContext(
            Coverage coverage,
            String detail,
            String categoryCode,
            String planCode,
            BigDecimal fipeValue,
            Integer discountPercent
    ) {
        String code = coverage.getCode() == null ? "" : coverage.getCode().trim().toUpperCase(java.util.Locale.ROOT);
        if (thirdPartyRuleSettingsService.supports(planCode) && "THIRD_PARTY_BASE".equals(code)) {
            BigDecimal amount = thirdPartyRuleSettingsService.limitFor(planCode, fipeValue, discountPercent);
            return "Cobertura de até " + coverageAmountLabel(amount);
        }
        var matchedRule = coverageRuleRepository.findByCoverage_IdOrderBySortOrderAscIdAsc(coverage.getId()).stream()
                .filter(rule -> rule.matches(categoryCode, fipeValue))
                .sorted(Comparator.comparing((CoverageRule rule) -> rule.getCategoryCode() == null ? 1 : 0)
                        .thenComparing(CoverageRule::getSortOrder))
                .findFirst();
        if (matchedRule.isPresent()) {
            CoverageRule rule = matchedRule.get();
            BigDecimal amount = discountPercent != null && discountPercent > 0 && rule.getDiscountedAmount() != null
                    ? rule.getDiscountedAmount()
                    : rule.getNormalAmount();
            String amountText = coverageAmountLabel(amount);
            if (detail == null || detail.isBlank()) return amountText;
            java.util.regex.Matcher moneyMatcher = java.util.regex.Pattern
                    .compile("R\\$\\s*[0-9.]+(?:,[0-9]{2})?(?:\\s*mil)?", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(detail);
            if (moneyMatcher.find()) {
                return moneyMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(amountText));
            }
            return detail + " • Limite: " + amountText;
        }
        // Demais efeitos de desconto são aplicados exclusivamente pelo motor
        // administrável de discount_rule_benefits. Não existe mais uma redução
        // escondida/legada em paralelo.
        return detail;
    }

    private boolean isProtectedThirdPartyRule(Quotation quotation, String coverageCode) {
        if (quotation == null || coverageCode == null) return false;
        return quotation.usesProtectedThirdPartyPlanRule() && "THIRD_PARTY_BASE".equalsIgnoreCase(coverageCode.trim());
    }

    private String coverageAmountLabel(BigDecimal amount) {
        if (amount == null) return "—";
        BigDecimal thousand = BigDecimal.valueOf(1000);
        BigDecimal[] division = amount.divideAndRemainder(thousand);
        if (division[1].compareTo(BigDecimal.ZERO) == 0) {
            return "R$ " + division[0].stripTrailingZeros().toPlainString() + " mil";
        }
        return java.text.NumberFormat.getCurrencyInstance(new java.util.Locale("pt", "BR")).format(amount);
    }


    @Transactional(readOnly = true)
    public QuoteResponse get(UUID id) {
        return toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public QuoteResponse getPublic(UUID id) {
        Quotation quotation = find(id);
        requireSelfService(quotation);
        return toResponse(quotation);
    }

    @Transactional
    public DecisionResponse decide(UUID id, DecisionRequest request) {
        Quotation quotation = find(id);
        if (quotation.getOrigin() != QuoteOrigin.CONSULTANT) {
            throw new IllegalArgumentException("Esta cotação foi criada pelo cliente e deve ser respondida pelo fluxo público.");
        }
        validateDecision(quotation, request);
        quotation.decide(request.decision());
        quotationRepository.saveAndFlush(quotation);
        if (request.decision() == QuoteStatus.ACCEPTED) commercialFinanceService.activateQuotation(quotation);
        return new DecisionResponse(toResponse(quotation), null, null);
    }

    @Transactional
    public DecisionResponse decidePublic(UUID id, DecisionRequest request) {
        Quotation quotation = find(id);
        requireSelfService(quotation);
        validateDecision(quotation, request);
        quotation.decide(request.decision());
        quotationRepository.saveAndFlush(quotation);
        if (request.decision() == QuoteStatus.ACCEPTED) commercialFinanceService.activateQuotation(quotation);

        String inspectionUrl = null;
        String whatsappUrl = null;
        if (request.decision() == QuoteStatus.ACCEPTED
                && quotation.getConsultant() != null
                && hasValidCpf(quotation.getCustomerCpf())) {
            InspectionResponse inspection = retratoService.ensureForSelfServiceQuote(quotation);
            inspectionUrl = inspection.publicUrl();
            whatsappUrl = buildSelfServiceWhatsappUrl(quotation, inspectionUrl);
        }

        return new DecisionResponse(toResponse(quotation), inspectionUrl, whatsappUrl);
    }

    private void validateDecision(Quotation quotation, DecisionRequest request) {
        if (request.decision() != QuoteStatus.ACCEPTED && request.decision() != QuoteStatus.DECLINED) {
            throw new IllegalArgumentException("Use ACCEPTED ou DECLINED para responder à proposta.");
        }
        if (quotation.getStatus() != QuoteStatus.CREATED && quotation.getStatus() != QuoteStatus.UNDER_REVIEW) {
            throw new IllegalArgumentException("Esta proposta já possui uma decisão registrada.");
        }
        if (OffsetDateTime.now().isAfter(quotation.getValidUntil())) {
            throw new IllegalArgumentException("Esta cotação expirou. Gere uma nova proposta.");
        }
    }

    private void requireSelfService(Quotation quotation) {
        if (quotation.getOrigin() != QuoteOrigin.SELF_SERVICE) {
            throw new IllegalArgumentException("Cotação pública não encontrada.");
        }
    }

    @Transactional(readOnly = true)
    public Quotation find(UUID id) {
        return quotationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cotação não encontrada."));
    }

    public String publicPdfUrl(Quotation quotation) {
        return publicApiUrl + "/api/quotes/" + quotation.getId() + "/pdf";
    }

    public QuoteResponse toResponse(Quotation quotation) {
        List<SelectedOptionalResponse> selectedOptionals = quotation.getSelectedOptionals().stream()
                .map(item -> new SelectedOptionalResponse(
                        item.getCoverageCode(),
                        item.getCoverageName(),
                        item.getDetail(),
                        item.getMonthlyPrice()
                ))
                .toList();

        List<InspectionPhotoResponse> inspectionPhotos = quotation.getInspectionPhotos().stream()
                .sorted(Comparator.comparingInt(item -> item.getSortOrder()))
                .map(item -> new InspectionPhotoResponse(
                        item.getId(),
                        item.getLabel(),
                        item.getFileName(),
                        item.getDriveFileUrl(),
                        item.getSortOrder()
                ))
                .toList();

        BigDecimal optionalMonthlyValue = quotation.getSelectedOptionals().stream()
                .map(item -> item.getMonthlyPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String teamWhatsappUrl = buildTeamWhatsappUrl(quotation);
        String clientWhatsappUrl = buildClientWhatsappUrl(quotation);
        String inspectionUrl = null;
        String selfServiceWhatsappUrl = null;

        if (quotation.getOrigin() == QuoteOrigin.SELF_SERVICE && quotation.getStatus() == QuoteStatus.ACCEPTED) {
            var inspection = retratoService.findForQuotation(quotation.getId()).orElse(null);
            if (inspection != null) {
                inspectionUrl = inspection.publicUrl();
                selfServiceWhatsappUrl = buildSelfServiceWhatsappUrl(quotation, inspectionUrl);
            }
        }

        return new QuoteResponse(
                quotation.getId(),
                quotation.getQuoteNumber(),
                quotation.getOrigin(),
                quotation.getConsultant() == null ? null : quotation.getConsultant().getId(),
                quotation.getConsultantName(),
                quotation.getCustomerName(),
                maskCpf(quotation.getCustomerCpf()),
                quotation.getWhatsapp(),
                quotation.getPlate(),
                quotation.getModel(),
                quotation.getManufactureYear(),
                quotation.isZeroKm(),
                quotation.getFipeValue(),
                quotation.getAuctionOrChassisRemarked(),
                quotation.getIndemnityFipePercent(),
                quotation.getCategoryCode(),
                quotation.getRegion(),
                quotation.getPricingRegion(),
                quotation.getMotorcycleOrigin(),
                quotation.getCategoryCode() != null && quotation.getCategoryCode().startsWith("MOTORCYCLE"),
                quotation.getMotorcycleCc(),
                quotation.getObservation(),
                quotation.getResidenceCep(),
                quotation.getResidenceAddress(),
                quotation.getBillingDueDay(),
                quotation.getFirstBillingDueDate(),
                quotation.getSelectedPlanCode(),
                quotation.getSelectedPlanName(),
                quotation.getBaseMonthlyValue(),
                quotation.getMandatoryMonthlyFee(),
                optionalMonthlyValue,
                quotation.getPreDiscountMonthlyValue(),
                quotation.getDiscountPercent(),
                quotation.getRearWindowBranding(),
                quotation.getMonthlyValue(),
                quotation.getOneTimeFee(),
                quotation.getMandatoryFeeDescription(),
                selectedOptionals,
                quotation.getStatus(),
                quotation.getCreatedAt(),
                quotation.getValidUntil(),
                quotation.getStatus() != QuoteStatus.CANCELLED
                        && !retratoService.hasPreservedFileForQuotation(quotation.getId())
                        && OffsetDateTime.now().isAfter(quotation.getValidUntil()),
                quotation.getDecidedAt(),
                quotation.getDriveFolderUrl(),
                quotation.getDrivePdfUrl(),
                quotation.getInspectionCompletedAt(),
                inspectionPhotos,
                teamWhatsappUrl,
                clientWhatsappUrl,
                inspectionUrl,
                selfServiceWhatsappUrl
        );
    }

    @Transactional(readOnly = true)
    public List<QuoteResponse> adminList() {
        return quotationRepository.findTop300ByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    private boolean isFipeThirdPartyCategory(String categoryCode) {
        return "CAR_NATIONAL".equalsIgnoreCase(categoryCode)
                || "CAR_IMPORTED".equalsIgnoreCase(categoryCode)
                || "UTILITY".equalsIgnoreCase(categoryCode);
    }

    private List<PlanCoverage> resolveSelectedOptionals(Plan plan, List<String> requestedCodes) {
        List<String> normalizedCodes = requestedCodes == null ? List.of() : requestedCodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(java.util.Locale.ROOT))
                .filter(code -> !code.isBlank())
                .toList();
        Set<String> uniqueCodes = new LinkedHashSet<>(normalizedCodes);
        if (uniqueCodes.size() != normalizedCodes.size()) {
            throw new IllegalArgumentException("O mesmo opcional não pode ser selecionado mais de uma vez.");
        }
        if (uniqueCodes.contains("FUNERAL") && uniqueCodes.contains("FUNERAL_FAMILY")) {
            throw new IllegalArgumentException("Escolha apenas uma modalidade de auxílio funeral.");
        }
        List<PlanCoverage> optionals = plan.getCoverages().stream()
                .filter(item -> isCoverageAvailableForPlan(plan, item))
                .filter(item -> item.getStatus() == CoverageStatus.OPTIONAL)
                .toList();

        List<PlanCoverage> selected = uniqueCodes.stream()
                .map(code -> optionals.stream()
                        .filter(item -> item.getCoverage().getCode().equals(code))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "O opcional " + code + " não está disponível para o plano selecionado."
                        )))
                .toList();

        selected.forEach(item -> {
            if (item.getMonthlyPrice() == null) {
                throw new IllegalArgumentException(
                        "O opcional " + item.getCoverage().getName() + " ainda não possui valor cadastrado."
                );
            }
        });
        return selected;
    }

    private java.util.Optional<PlanOption> toPlanOption(Plan plan, BigDecimal fipeValue, Integer manufactureYear, PricingRegion pricingRegion) {
        return pricingService.calculateBreakdown(plan, fipeValue, pricingRegion).map(pricing -> new PlanOption(
                plan.getCode(),
                plan.getName(),
                plan.getSubtitle(),
                pricing.tableMonthlyValue(),
                pricing.mandatoryMonthlyFee(),
                pricing.totalMonthlyValue(),
                pricing.oneTimeFee(),
                pricing.mandatoryFeeDescription(),
                plan.getCoverages().stream()
                        .filter(item -> isCoverageAvailableForPlan(plan, item))
                        .map(item -> new CoverageOption(
                                item.getCoverage().getCode(),
                                item.getCoverage().getName(),
                                item.getStatus(),
                                coverageDetailForContext(item.getCoverage(), item.getEffectiveDetail(), plan.getCategory().getCode(), plan.getCode(), fipeValue, 0),
                                coverageDetailForContext(item.getCoverage(), item.getEffectiveDetail(), plan.getCategory().getCode(), plan.getCode(), fipeValue, 5),
                                item.getMonthlyPrice()
                        ))
                        .toList(),
                discountOptions(plan, fipeValue, manufactureYear)
        ));
    }

    private PlanOption toPlanOption(Plan plan, PricingService.PricingResult pricing, BigDecimal fipeValue, Integer manufactureYear) {
        return new PlanOption(
                plan.getCode(),
                plan.getName(),
                plan.getSubtitle(),
                pricing.tableMonthlyValue(),
                pricing.mandatoryMonthlyFee(),
                pricing.totalMonthlyValue(),
                pricing.oneTimeFee(),
                pricing.mandatoryFeeDescription(),
                plan.getCoverages().stream()
                        .filter(item -> isCoverageAvailableForPlan(plan, item))
                        .map(item -> new CoverageOption(
                                item.getCoverage().getCode(),
                                item.getCoverage().getName(),
                                item.getStatus(),
                                coverageDetailForContext(item.getCoverage(), item.getEffectiveDetail(), plan.getCategory().getCode(), plan.getCode(), fipeValue, 0),
                                coverageDetailForContext(item.getCoverage(), item.getEffectiveDetail(), plan.getCategory().getCode(), plan.getCode(), fipeValue, 5),
                                item.getMonthlyPrice()
                        ))
                        .toList(),
                discountOptions(plan, fipeValue, manufactureYear)
        );
    }

    private boolean isCoverageAvailableForPlan(Plan plan, PlanCoverage item) {
        if (plan == null || item == null || item.getCoverage() == null) return false;
        Coverage coverage = item.getCoverage();
        String categoryCode = plan.getCategory() == null ? "" : plan.getCategory().getCode();
        if (item.getStatus() == CoverageStatus.OPTIONAL
                && !coverage.allowsOptionalForVehicleCategory(categoryCode)) {
            return false;
        }
        if (Quotation.isDiscountExcludedBenefit(coverage.getCode())) {
            String planCode = plan.getCode() == null ? "" : plan.getCode().trim().toUpperCase(Locale.ROOT);
            return ("CAR_COMPLETO".equals(planCode) || "UTILITY_COMPLETO".equals(planCode))
                    && item.getStatus() == CoverageStatus.INCLUDED;
        }
        return true;
    }

    private List<DiscountOption> discountOptions(Plan plan, BigDecimal fipeValue, Integer manufactureYear) {
        return discountRuleService.optionsForPlan(plan, fipeValue, manufactureYear).stream()
                .map(option -> {
                    DiscountRule rule = option.rule();
                    List<DiscountBenefitEffect> effects = rule.getBenefitRules().stream()
                            .filter(item -> item.getCoverage() != null)
                            .map(item -> new DiscountBenefitEffect(
                                    item.getCoverage().getCode(), item.getAction(), item.getDetailOverride(), item.getAmountOverride()
                            ))
                            .toList();
                    return new DiscountOption(
                            rule.getId(), rule.getName(), rule.getPercent(), option.available(), option.unavailableReason(),
                            Boolean.TRUE.equals(rule.getRequiresRearWindowPerforation()), option.requiredBranding(), option.conditionText(),
                            Boolean.TRUE.equals(rule.getApplyToBase()), Boolean.TRUE.equals(rule.getApplyToMandatoryFee()),
                            Boolean.TRUE.equals(rule.getApplyToOptionals()), effects
                    );
                })
                .toList();
    }

    private java.util.Optional<PlanOption> promotionalMotorcycleOption(
            BigDecimal fipeValue, Integer motorcycleCc, String promoMotorcycleTier, Integer manufactureYear
    ) {
        if (motorcycleCc == null) return java.util.Optional.empty();
        return planRepository.findAvailableByCode("MOTO_PROMO_2026")
                .flatMap(plan -> pricingService.calculatePromotionalMotorcycle(plan, fipeValue, motorcycleCc, promoMotorcycleTier)
                        .map(pricing -> toPlanOption(plan, pricing, fipeValue, manufactureYear)));
    }

    private String validatePromotionalMotorcycleTier(String categoryCode, String tierCode) {
        if (!"MOTORCYCLE_PROMO_2026".equals(categoryCode)) return null;
        if (tierCode == null || tierCode.isBlank()) {
            throw new IllegalArgumentException("Selecione uma faixa da Tabela Promocional de Motocicletas.");
        }
        return tierCode.trim();
    }

    private Integer validateMotorcycleSelection(String categoryCode, Boolean motorcycle, Integer motorcycleCc) {
        boolean motorcycleCategory = categoryCode != null && categoryCode.startsWith("MOTORCYCLE");
        boolean declaredMotorcycle = motorcycle == null ? motorcycleCategory : Boolean.TRUE.equals(motorcycle);
        if (motorcycleCategory != declaredMotorcycle) {
            throw new IllegalArgumentException(motorcycleCategory
                    ? "A categoria selecionada é de motocicleta."
                    : "A indicação de motocicleta só pode ser usada nas categorias de motocicletas.");
        }
        if (!motorcycleCategory) return null;
        if (!"MOTORCYCLE_PROMO_2026".equals(categoryCode)) {
            return null;
        }
        if (motorcycleCc == null || motorcycleCc < 1 || motorcycleCc > 2500) {
            throw new IllegalArgumentException("Selecione uma faixa da Tabela Promocional de Motocicletas.");
        }
        return motorcycleCc;
    }

    private String buildQuoteNumber() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "NH-" + Year.now().getValue() + "-" + suffix;
    }

    private String validateAndNormalizePlate(String plate, boolean zeroKm) {
        String normalized = plate == null ? "" : plate.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (zeroKm && normalized.isBlank()) return null;
        if (!normalized.matches("^[A-Z0-9]{7,10}$")) {
            throw new IllegalArgumentException(zeroKm
                    ? "Informe uma placa válida ou deixe o campo vazio para veículo 0 km."
                    : "Informe a placa do veículo.");
        }
        return normalized;
    }

    private String vehiclePlateLabel(Quotation quotation) {
        return quotation.getPlate() == null || quotation.getPlate().isBlank()
                ? "Veículo 0 km — sem placa"
                : quotation.getPlate();
    }

    private MotorcycleOrigin validateMotorcycleOrigin(String categoryCode, MotorcycleOrigin motorcycleOrigin) {
        boolean motorcycle = categoryCode != null && categoryCode.startsWith("MOTORCYCLE");
        boolean promotional = "MOTORCYCLE_PROMO_2026".equals(categoryCode);
        if (motorcycle && !promotional && motorcycleOrigin == null) {
            throw new IllegalArgumentException("Informe a origem da moto para aplicar a tabela correta.");
        }
        if ((!motorcycle || promotional) && motorcycleOrigin != null) {
            throw new IllegalArgumentException(promotional
                    ? "A tabela promocional não utiliza origem da moto."
                    : "A origem da moto só pode ser informada para motocicletas.");
        }
        return motorcycle && !promotional ? motorcycleOrigin : null;
    }

    private void validateYear(Integer year) {
        int currentYear = Year.now().getValue() + 1;
        if (year < 1950 || year > currentYear) {
            throw new IllegalArgumentException("Ano de fabricação inválido.");
        }
    }

    private String buildTeamWhatsappUrl(Quotation quotation) {
        String teamWhatsappNumber = communicationSettings.teamWhatsapp();
        if (teamWhatsappNumber == null || teamWhatsappNumber.isBlank()) return null;
        String pdfUrl = quotation.getDrivePdfUrl() == null || quotation.getDrivePdfUrl().isBlank()
                ? publicPdfUrl(quotation)
                : quotation.getDrivePdfUrl();
        String message = "Cotação " + quotation.getQuoteNumber()
                + "\nCliente: " + quotation.getCustomerName()
                + "\nOrigem: " + originLabel(quotation)
                + "\nResponsável: " + quotation.getConsultantName()
                + "\nPlaca: " + vehiclePlateLabel(quotation)
                + "\nPlano: " + quotation.getSelectedPlanName()
                + (quotation.getDriveFolderUrl() == null ? "" : "\nPasta da vistoria: " + quotation.getDriveFolderUrl())
                + "\nPDF: " + pdfUrl;
        return whatsappUrl(teamWhatsappNumber, message);
    }

    private String buildClientWhatsappUrl(Quotation quotation) {
        if (quotation.getWhatsapp() == null || quotation.getWhatsapp().isBlank()) return null;
        String message = "Olá, " + quotation.getCustomerName()
                + "! Segue o PDF da sua cotação " + quotation.getQuoteNumber()
                + " da " + TenantIdentity.legalName() + ": " + publicPdfUrl(quotation);
        return whatsappUrl(quotation.getWhatsapp(), message);
    }

    private String buildSelfServiceWhatsappUrl(Quotation quotation, String inspectionUrl) {
        String teamWhatsappNumber = communicationSettings.teamWhatsapp();
        if (teamWhatsappNumber == null || teamWhatsappNumber.isBlank()) return null;
        String message = "Olá! Fiz uma cotação pelo site da " + TenantIdentity.displayName() + " e aceitei a proposta."
                + "\n\nCotação: " + quotation.getQuoteNumber()
                + "\nNome: " + quotation.getCustomerName()
                + "\nPlaca: " + vehiclePlateLabel(quotation)
                + "\nPlano: " + quotation.getSelectedPlanName()
                + "\nValor mensal: R$ " + quotation.getMonthlyValue().toPlainString().replace('.', ',')
                + "\n\nLink para enviar as fotos e o vídeo da vistoria digital: " + inspectionUrl;
        return whatsappUrl(teamWhatsappNumber, message);
    }

    private String originLabel(Quotation quotation) {
        return quotation.getOrigin() == QuoteOrigin.SELF_SERVICE ? "Cliente pelo site" : "Consultor";
    }

    private String whatsappUrl(String phone, String message) {
        String digits = phone.replaceAll("\\D", "");
        String normalizedPhone = digits.startsWith("55") ? digits : "55" + digits;
        return "https://wa.me/" + normalizedPhone + "?text="
                + UriUtils.encode(message, StandardCharsets.UTF_8);
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        return phone.replaceAll("\\D", "");
    }

    private static String normalizeCpf(String cpf) {
        return cpf == null ? "" : cpf.replaceAll("\\D", "");
    }

    private static String normalizeOptionalCpf(String cpf) {
        if (cpf == null || cpf.isBlank()) return null;
        return normalizeAndValidateCpf(cpf);
    }

    private static String normalizeAndValidateCpf(String cpf) {
        String normalized = normalizeCpf(cpf);
        if (!validCpf(normalized)) throw new IllegalArgumentException("Informe um CPF válido.");
        return normalized;
    }

    private static boolean hasValidCpf(String cpf) {
        return validCpf(normalizeCpf(cpf));
    }

    private static String maskCpf(String cpf) {
        return cpf != null && cpf.length() == 11
                ? "***." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-**"
                : null;
    }

    private static boolean validCpf(String cpf) {
        if (cpf == null || !cpf.matches("\\d{11}") || cpf.chars().distinct().count() == 1) return false;
        try {
            int sum = 0;
            for (int i = 0; i < 9; i++) sum += Character.digit(cpf.charAt(i), 10) * (10 - i);
            int first = 11 - (sum % 11);
            if (first >= 10) first = 0;
            sum = 0;
            for (int i = 0; i < 10; i++) sum += Character.digit(cpf.charAt(i), 10) * (11 - i);
            int second = 11 - (sum % 11);
            if (second >= 10) second = 0;
            return first == Character.digit(cpf.charAt(9), 10)
                    && second == Character.digit(cpf.charAt(10), 10);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:8080";
        return value.replaceAll("/+$", "");
    }

    private record PlanSelection(
            Plan plan,
            PricingService.PricingResult pricing,
            List<PlanCoverage> optionals
    ) {}
}
