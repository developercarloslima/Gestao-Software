package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.ContractChangeDtos.ContractChangeDecisionResponse;
import br.com.nh.cotacao.dto.ContractChangeDtos.OptionalItem;
import br.com.nh.cotacao.dto.ContractChangeDtos.PublicContractChangeResponse;
import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.entity.InspectionAsset;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.Quotation;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.QuotationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class InspectionContractChangeService {
    private final InspectionRequestRepository inspectionRepository;
    private final QuotationRepository quotationRepository;
    private final CatalogChangeAuditRepository auditRepository;
    private final RetratoPdfService pdfService;
    private final InspectionAssetStorageService storageService;
    private final CommercialPricingService commercialPricingService;
    private final InspectionFipeSelectionService fipeSelectionService;

    public InspectionContractChangeService(
            InspectionRequestRepository inspectionRepository,
            QuotationRepository quotationRepository,
            CatalogChangeAuditRepository auditRepository,
            RetratoPdfService pdfService,
            InspectionAssetStorageService storageService,
            CommercialPricingService commercialPricingService,
            InspectionFipeSelectionService fipeSelectionService
    ) {
        this.inspectionRepository = inspectionRepository;
        this.quotationRepository = quotationRepository;
        this.auditRepository = auditRepository;
        this.pdfService = pdfService;
        this.storageService = storageService;
        this.commercialPricingService = commercialPricingService;
        this.fipeSelectionService = fipeSelectionService;
    }

    @Transactional(readOnly = true)
    public PublicContractChangeResponse publicStatus(String token) {
        InspectionRequest inspection = inspectionRepository.findByPublicToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Link de confirmação não encontrado."));
        Quotation quotation = requireQuotation(inspection);
        return toPublicResponse(inspection, quotation);
    }

    @Transactional
    public ContractChangeDecisionResponse decide(String token, boolean accepted, Boolean keepOptionals) {
        InspectionRequest inspection = inspectionRepository.findByPublicTokenForUpdate(token)
                .orElseThrow(() -> new IllegalArgumentException("Link de confirmação não encontrado."));
        Quotation quotation = requireQuotation(inspection);

        if (!inspection.hasPendingContractChange()) {
            throw new IllegalArgumentException("Não existe alteração de valor aguardando confirmação para esta vistoria.");
        }

        BigDecimal proposedFipe = inspection.getPendingContractFipeValue();
        BigDecimal proposedMonthly = inspection.getPendingContractMonthlyValue();
        Integer proposedDiscount = inspection.getPendingContractDiscountPercent() == null
                ? quotation.getDiscountPercent() : inspection.getPendingContractDiscountPercent();
        var proposedBranding = inspection.getPendingContractRearWindowBranding() == null
                ? quotation.getRearWindowBranding() : inspection.getPendingContractRearWindowBranding();
        Set<String> proposedBenefits = inspection.pendingContractBenefitCodesSet();
        if (proposedBenefits.isEmpty() && inspection.getPendingContractBenefitCodes() == null) {
            proposedBenefits = quotation.finalSelectedCoverageCodes();
        }
        proposedBenefits = commercialPricingService.filterToCurrentCatalog(quotation, proposedBenefits);
        BigDecimal oldFipe = quotation.getFipeValue();
        BigDecimal oldMonthly = quotation.getMonthlyValue();
        List<OptionalItem> proposedOptionals = proposedOptionalItems(quotation, proposedFipe, proposedBenefits, proposedDiscount);
        boolean hasOptionals = !proposedOptionals.isEmpty();

        if (!accepted) {
            fipeSelectionService.drop(inspection.getId());
            inspection.resolveContractChange(false, null);
            inspectionRepository.flush();
            auditRepository.save(CatalogChangeAudit.createText(
                    "CONTRACT_CHANGE_DECISION", null, inspection.getId().toString(),
                    "Associado não concordou com a alteração contratual",
                    summary(oldFipe, oldMonthly, quotation.selectedOptionalContractMonthlyValue()),
                    "recusado; FIPE proposta=" + proposedFipe + "; mensalidade proposta=" + proposedMonthly,
                    "ASSOCIADO"
            ));
            return new ContractChangeDecisionResponse(
                    false, null, oldFipe, oldMonthly,
                    "A alteração não foi aceita. Os valores anteriores do contrato foram mantidos."
            );
        }

        if (hasOptionals && keepOptionals == null) {
            throw new IllegalArgumentException("Informe se deseja manter os adicionais já contratados.");
        }

        boolean keep = !hasOptionals || Boolean.TRUE.equals(keepOptionals);
        BigDecimal optionalsBefore = quotation.selectedOptionalContractMonthlyValue();
        BigDecimal proposedOptionalTotal = proposedOptionals.stream()
                .map(OptionalItem::monthlyPrice)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        Set<String> benefitsToApply = new LinkedHashSet<>(proposedBenefits);
        BigDecimal targetMonthly = proposedMonthly;
        if (!keep && hasOptionals) {
            Set<String> optionalCodes = proposedOptionals.stream().map(OptionalItem::code).collect(java.util.stream.Collectors.toSet());
            benefitsToApply.removeIf(optionalCodes::contains);
            targetMonthly = proposedMonthly.subtract(proposedOptionalTotal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        var pricing = commercialPricingService.calculate(quotation, proposedFipe, proposedDiscount, benefitsToApply);
        // A confirmação nunca consolida uma mensalidade antiga/manual. Mesmo uma
        // proposta pendente criada por versão anterior é recalculada neste momento
        // com a tabela vigente, FIPE, rastreador/taxas, adicionais atuais e desconto.
        targetMonthly = pricing.finalMonthlyValue();
        commercialPricingService.synchronizeSelectedOptionals(quotation, benefitsToApply);
        quotation.applyCommercialRevision(
                proposedFipe, targetMonthly, proposedDiscount, proposedBranding, benefitsToApply,
                pricing.planBaseMonthlyValue(), pricing.mandatoryMonthlyFee(), pricing.oneTimeFee(),
                pricing.mandatoryFeeDescription(), false
        );
        boolean linkedFipe = fipeSelectionService.resolve(inspection.getId(), quotation, proposedFipe);
        if (!linkedFipe && oldFipe.compareTo(proposedFipe) != 0) {
            quotation.updateFipeMetadata(null, null, null);
        }
        quotationRepository.saveAndFlush(quotation);

        inspection.resolveContractChange(true, hasOptionals ? keep : null);
        inspectionRepository.flush();

        refreshReportIfPresent(inspection);

        auditRepository.save(CatalogChangeAudit.createText(
                "CONTRACT_CHANGE_DECISION", null, inspection.getId().toString(),
                "Associado confirmou a alteração contratual",
                summary(oldFipe, oldMonthly, optionalsBefore),
                summary(quotation.getFipeValue(), quotation.getMonthlyValue(), quotation.selectedOptionalContractMonthlyValue())
                        + "; manter adicionais=" + (hasOptionals ? (keep ? "sim" : "não") : "não se aplica"),
                "ASSOCIADO"
        ));

        return new ContractChangeDecisionResponse(
                true,
                hasOptionals ? keep : null,
                quotation.getFipeValue(),
                quotation.getMonthlyValue(),
                hasOptionals && !keep
                        ? "Novo valor confirmado sem os adicionais anteriores."
                        : "Novo valor confirmado com sucesso."
        );
    }

    private PublicContractChangeResponse toPublicResponse(InspectionRequest inspection, Quotation quotation) {
        int discountPercent = inspection.getPendingContractDiscountPercent() == null
                ? (quotation.getDiscountPercent() == null ? 0 : quotation.getDiscountPercent())
                : inspection.getPendingContractDiscountPercent();
        Set<String> proposedBenefits = inspection.pendingContractBenefitCodesSet();
        if (proposedBenefits.isEmpty() && inspection.getPendingContractBenefitCodes() == null) {
            proposedBenefits = quotation.finalSelectedCoverageCodes();
        }
        proposedBenefits = commercialPricingService.filterToCurrentCatalog(quotation, proposedBenefits);
        BigDecimal proposedFipe = inspection.getPendingContractFipeValue() == null
                ? quotation.getFipeValue() : inspection.getPendingContractFipeValue();
        List<OptionalItem> optionals = proposedOptionalItems(quotation, proposedFipe, proposedBenefits, discountPercent);
        BigDecimal optionalTotal = optionals.stream()
                .map(OptionalItem::monthlyPrice)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal proposedWithOptionals = inspection.getPendingContractMonthlyValue();
        BigDecimal proposedWithoutOptionals;
        if (inspection.hasPendingContractChange()) {
            // Toda proposta pendente é recalculada ao abrir o link. Isso também
            // saneia propostas legadas que tenham guardado R$ 168 (ou qualquer
            // outro total histórico) quando o catálogo atual produz outro valor.
            var withOptionalsPricing = commercialPricingService.calculate(
                    quotation, proposedFipe, discountPercent, proposedBenefits
            );
            proposedWithOptionals = withOptionalsPricing.finalMonthlyValue();

            if (optionals.isEmpty()) {
                proposedWithoutOptionals = proposedWithOptionals;
            } else {
                Set<String> benefitsWithoutOptionals = new LinkedHashSet<>(proposedBenefits);
                Set<String> optionalCodes = optionals.stream()
                        .map(OptionalItem::code)
                        .collect(java.util.stream.Collectors.toSet());
                benefitsWithoutOptionals.removeIf(optionalCodes::contains);
                proposedWithoutOptionals = commercialPricingService.calculate(
                        quotation, proposedFipe, discountPercent, benefitsWithoutOptionals
                ).finalMonthlyValue();
            }
        } else {
            proposedWithoutOptionals = proposedWithOptionals == null
                    ? null
                    : proposedWithOptionals.subtract(optionalTotal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        String vehicle = quotation.getModel()
                + (quotation.getPlate() == null || quotation.getPlate().isBlank() ? "" : " · " + quotation.getPlate());

        return new PublicContractChangeResponse(
                inspection.hasPendingContractChange(),
                inspection.getAssociateName(),
                vehicle,
                quotation.getSelectedPlanName(),
                quotation.getFipeValue(),
                inspection.getPendingContractFipeValue(),
                quotation.getMonthlyValue(),
                proposedWithOptionals,
                proposedWithoutOptionals,
                optionalTotal,
                optionals,
                inspection.getContractChangeRequestedAt()
        );
    }

    private List<OptionalItem> proposedOptionalItems(
            Quotation quotation, BigDecimal requestedFipe, Set<String> benefitCodes, int discountPercent
    ) {
        // A tela de confirmação do associado também usa o catálogo atual. Assim,
        // um serviço adicional corrigido no Admin nunca reaparece com preço antigo
        // do snapshot da cotação. A FIPE proposta também é usada para validar a
        // elegibilidade de descontos configurados por faixa no painel.
        return commercialPricingService.optionalPricingItems(quotation, requestedFipe, benefitCodes, discountPercent).stream()
                .map(item -> new OptionalItem(
                        item.code(), item.name(), item.detail(), item.discountedMonthlyPrice()
                ))
                .toList();
    }

    private Quotation requireQuotation(InspectionRequest inspection) {
        if (inspection.getQuotation() == null) {
            throw new IllegalArgumentException("Esta vistoria não possui cotação vinculada.");
        }
        return inspection.getQuotation();
    }

    private void refreshReportIfPresent(InspectionRequest inspection) {
        InspectionAsset report = inspection.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                .findFirst()
                .orElse(null);
        if (report == null) return;

        byte[] reportBytes = pdfService.generate(inspection);
        storageService.replaceGeneratedReport(
                inspection,
                "Relatório da vistoria atualizado",
                "relatorio-retrato-nh.pdf",
                report.getSortOrder(),
                reportBytes
        );
        inspectionRepository.flush();
    }

    private String summary(BigDecimal fipe, BigDecimal monthly, BigDecimal optionals) {
        return "FIPE=" + fipe + "; mensalidade=" + monthly + "; adicionais=" + optionals;
    }
}
