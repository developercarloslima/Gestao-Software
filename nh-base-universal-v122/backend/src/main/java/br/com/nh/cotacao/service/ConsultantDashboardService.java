package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.dto.ConsultantDashboardDtos.*;
import br.com.nh.cotacao.dto.InspectionDtos.InspectionResponse;
import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.entity.Quotation;
import br.com.nh.cotacao.entity.QuoteStatus;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.QuotationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConsultantDashboardService {
    private final ConsultantService consultantService;
    private final QuotationRepository quotationRepository;
    private final InspectionRequestRepository inspectionRepository;
    private final RetratoService retratoService;
    private final InspectionAssetStorageService storageService;
    private final QuoteService quoteService;

    public ConsultantDashboardService(
            ConsultantService consultantService,
            QuotationRepository quotationRepository,
            InspectionRequestRepository inspectionRepository,
            RetratoService retratoService,
            InspectionAssetStorageService storageService,
            QuoteService quoteService
    ) {
        this.consultantService = consultantService;
        this.quotationRepository = quotationRepository;
        this.inspectionRepository = inspectionRepository;
        this.retratoService = retratoService;
        this.storageService = storageService;
        this.quoteService = quoteService;
    }

    @Transactional
    public ConsultantDashboardResponse dashboard(UUID consultantId) {
        Consultant consultant = consultantService.findActive(consultantId);
        List<Quotation> quoteEntities = findConsultantQuotes(consultant);
        List<InspectionRequest> inspections = new ArrayList<>(findConsultantInspections(consultant));

        // Corrige automaticamente registros históricos que possuem apenas o nome do consultor.
        quoteEntities.forEach(item -> repairOwnership(item, consultant));
        inspections.forEach(item -> repairOwnership(item, consultant));

        Map<UUID, InspectionRequest> inspectionsByQuote = inspections.stream()
                .filter(item -> item.getQuotation() != null)
                .collect(Collectors.toMap(
                        item -> item.getQuotation().getId(),
                        Function.identity(),
                        (left, right) -> left.getCreatedAt().isAfter(right.getCreatedAt()) ? left : right
                ));

        // A abertura do painel não cria mais vistorias automaticamente.
        // A nova vistoria só nasce quando o consultor abre o Retrato NH a partir
        // de uma cotação ACEITA e confirma a geração do link.

        inspections.sort(Comparator.comparing(InspectionRequest::getCreatedAt).reversed());

        List<ConsultantQuoteSummary> quotes = quoteEntities.stream()
                .map(item -> toQuote(item, inspectionsByQuote.get(item.getId())))
                .toList();
        List<ConsultantInspectionSummary> inspectionItems = inspections.stream()
                .map(this::toInspection)
                .toList();

        return new ConsultantDashboardResponse(consultant.getId(), consultant.getName(), quotes, inspectionItems);
    }

    @Transactional
    public ConsultantInspectionSummary ensureInspection(UUID consultantId, UUID quoteId, String requestedCpf) {
        Consultant consultant = consultantService.findActive(consultantId);
        Quotation quotation = findOwnedQuotation(consultant, quoteId);
        repairOwnership(quotation, consultant);
        if (quotation.getStatus() != QuoteStatus.ACCEPTED) {
            throw new IllegalArgumentException("A cotação precisa estar aceita para iniciar a nova vistoria.");
        }
        quoteService.ensureCustomerCpf(quotation, requestedCpf);

        InspectionResponse created = retratoService.ensureForQuotation(quotation);
        InspectionRequest inspection = inspectionRepository.findById(created.id())
                .orElseThrow(() -> new IllegalStateException("A vistoria foi criada, mas não pôde ser carregada."));
        repairOwnership(inspection, consultant);
        return toInspection(inspection);
    }

    @Transactional
    public ConsultantQuoteSummary redoQuote(UUID consultantId, UUID quoteId, String requestedCpf) {
        Consultant consultant = consultantService.findActive(consultantId);
        Quotation source = findOwnedQuotation(consultant, quoteId);
        repairOwnership(source, consultant);
        Quotation recreated = quoteService.recreateForConsultant(source, consultant, requestedCpf);
        return toQuote(recreated, null);
    }

    @Transactional
    public ConsultantQuoteSummary updateQuoteDetails(
            UUID consultantId,
            UUID quoteId,
            UpdateQuoteDetailsRequest request
    ) {
        Consultant consultant = consultantService.findActive(consultantId);
        Quotation quotation = findOwnedQuotation(consultant, quoteId);
        repairOwnership(quotation, consultant);
        InspectionRequest inspection = inspectionRepository.findByQuotation_Id(quotation.getId()).orElse(null);
        assertNotExpiredWithoutPreservedFiles(quotation, inspection);

        Map<String, String> immutableBefore = immutableQuoteSnapshot(quotation);
        quoteService.updateNonPricingData(
                quotation,
                request.customerName(),
                request.cpf(),
                request.whatsapp(),
                request.plate(),
                request.model(),
                request.manufactureYear(),
                request.zeroKm(),
                request.observation()
        );

        if (inspection != null) {
            repairOwnership(inspection, consultant);
            inspection.updateAssociateData(
                    quotation.getCustomerName(),
                    quotation.getCustomerCpf(),
                    quotation.getWhatsapp(),
                    quotation.getPlate()
            );
            inspection.updateEditableAssociateVehicleData(
                    quotation.getCustomerName(), quotation.getWhatsapp(), quotation.getModel(), quotation.getManufactureYear()
            );
        }

        Map<String, String> immutableAfter = immutableQuoteSnapshot(quotation);
        if (!immutableBefore.equals(immutableAfter)) {
            throw new IllegalStateException("A edição tentou alterar informações protegidas do cálculo da cotação.");
        }

        quotationRepository.flush();
        if (inspection != null) inspectionRepository.flush();
        return toQuote(quotation, inspection);
    }

    @Transactional
    public ConsultantQuoteSummary decideQuote(UUID consultantId, UUID quoteId, QuoteStatus decision) {
        Consultant consultant = consultantService.findActive(consultantId);
        Quotation quotation = findOwnedQuotation(consultant, quoteId);
        repairOwnership(quotation, consultant);

        if (decision != QuoteStatus.ACCEPTED && decision != QuoteStatus.DECLINED) {
            throw new IllegalArgumentException("A decisão deve ser ACCEPTED ou DECLINED.");
        }
        if (quotation.getStatus() == QuoteStatus.ACCEPTED || quotation.getStatus() == QuoteStatus.CANCELLED) {
            throw new IllegalArgumentException("Esta cotação não pode receber uma nova decisão pelo painel do consultor.");
        }
        if (OffsetDateTime.now().isAfter(quotation.getValidUntil())) {
            throw new IllegalArgumentException("Esta cotação expirou. Refaça a cotação antes de registrar uma nova decisão.");
        }

        quotation.decide(decision);
        quotationRepository.saveAndFlush(quotation);
        InspectionRequest inspection = inspectionRepository.findByQuotation_Id(quotation.getId()).orElse(null);
        return toQuote(quotation, inspection);
    }

    @Transactional
    public void deleteQuote(UUID consultantId, UUID quoteId) {
        Consultant consultant = consultantService.findActive(consultantId);
        Quotation quotation = findOwnedQuotation(consultant, quoteId);
        quotationRepository.delete(quotation);
        quotationRepository.flush();
    }

    private List<Quotation> findConsultantQuotes(Consultant consultant) {
        Map<UUID, Quotation> merged = new LinkedHashMap<>();
        quotationRepository.findAllByConsultant_IdOrderByCreatedAtDesc(consultant.getId())
                .forEach(item -> merged.put(item.getId(), item));
        quotationRepository.findAllByConsultantNameIgnoreCaseOrderByCreatedAtDesc(consultant.getName())
                .forEach(item -> merged.putIfAbsent(item.getId(), item));
        return merged.values().stream()
                .filter(item -> belongsTo(item, consultant))
                .sorted(Comparator.comparing(Quotation::getCreatedAt).reversed())
                .toList();
    }

    private List<InspectionRequest> findConsultantInspections(Consultant consultant) {
        Map<UUID, InspectionRequest> merged = new LinkedHashMap<>();
        inspectionRepository.findAllByConsultant_IdOrderByCreatedAtDesc(consultant.getId())
                .forEach(item -> merged.put(item.getId(), item));
        inspectionRepository.findAllByConsultantNameIgnoreCaseOrderByCreatedAtDesc(consultant.getName())
                .forEach(item -> merged.putIfAbsent(item.getId(), item));
        return merged.values().stream()
                .filter(item -> belongsTo(item, consultant))
                .sorted(Comparator.comparing(InspectionRequest::getCreatedAt).reversed())
                .toList();
    }

    private Quotation findOwnedQuotation(Consultant consultant, UUID quoteId) {
        Quotation quotation = quotationRepository.findById(quoteId)
                .orElseThrow(() -> new IllegalArgumentException("Cotação não encontrada."));
        if (!belongsTo(quotation, consultant)) {
            throw new IllegalArgumentException("Esta cotação não pertence ao consultor selecionado.");
        }
        return quotation;
    }

    private boolean belongsTo(Quotation quotation, Consultant consultant) {
        if (quotation.getConsultant() != null
                && consultant.getId().equals(quotation.getConsultant().getId())) return true;
        return sameConsultantName(quotation.getConsultantName(), consultant);
    }

    private boolean belongsTo(InspectionRequest inspection, Consultant consultant) {
        if (inspection.getConsultant() != null
                && consultant.getId().equals(inspection.getConsultant().getId())) return true;
        return sameConsultantName(inspection.getConsultantName(), consultant);
    }

    private boolean sameConsultantName(String activityName, Consultant consultant) {
        return Consultant.normalize(activityName).equals(consultant.getNormalizedName());
    }

    private void repairOwnership(Quotation quotation, Consultant consultant) {
        if (quotation.getConsultant() == null && sameConsultantName(quotation.getConsultantName(), consultant)) {
            quotation.assignConsultant(consultant);
        }
    }

    private void repairOwnership(InspectionRequest inspection, Consultant consultant) {
        if (inspection.getConsultant() == null && sameConsultantName(inspection.getConsultantName(), consultant)) {
            inspection.assignConsultant(consultant);
        }
    }

    private void assertNotExpiredWithoutPreservedFiles(Quotation quotation, InspectionRequest inspection) {
        if (inspection != null && inspection.hasAnyPreservedFile()) return;
        boolean quotationExpired = quotation != null && quotation.getValidUntil() != null
                && OffsetDateTime.now().isAfter(quotation.getValidUntil());
        boolean inspectionExpired = inspection != null && inspection.isExpired();
        if (quotationExpired || inspectionExpired) {
            throw new IllegalArgumentException("Vistoria/cotação vencida, precisa ser refeita.");
        }
    }

    private boolean hasStoredCpf(Quotation quotation) {
        return quoteService.hasValidCustomerCpf(quotation);
    }

    private ConsultantQuoteSummary toQuote(Quotation item, InspectionRequest inspection) {
        boolean hasFiles = hasFiles(inspection);
        boolean expired = item.getStatus() != QuoteStatus.CANCELLED
                && !hasFiles
                && OffsetDateTime.now().isAfter(item.getValidUntil());
        InspectionRequestStatus inspectionStatus = displayStatus(inspection);
        OffsetDateTime inspectionCompletedAt = inspection == null ? item.getInspectionCompletedAt() : inspection.getCompletedAt();
        InspectionResponse inspectionResponse = inspection == null ? null : retratoService.toResponse(inspection);
        return new ConsultantQuoteSummary(
                item.getId(), item.getQuoteNumber(), item.getCustomerName(), item.getCustomerCpf(), item.getWhatsapp(),
                item.getPlate(), item.isZeroKm(), item.getModel(), item.getManufactureYear(), item.getCategoryCode(),
                item.getMotorcycleCc(), item.getObservation(), item.getSelectedPlanName(), item.getMonthlyValue(), item.getStatus(), item.getCreatedAt(), item.getValidUntil(),
                expired,
                hasStoredCpf(item),
                "/api/quotes/" + item.getId() + "/pdf",
                inspection == null ? null : inspection.getId(),
                inspectionStatus,
                inspectionCompletedAt,
                inspectionResponse == null ? null : inspectionResponse.publicUrl(),
                inspectionResponse == null ? null : inspectionResponse.whatsappUrl(),
                null,
                hasFiles,
                inspection == null ? 0 : (int) inspection.getAssets().stream().filter(storageService::isAvailable).count()
        );
    }

    /**
     * Fotografia dos campos que formam o preço e o conteúdo comercial da cotação.
     * Qualquer mudança acidental faz a transação inteira ser revertida.
     */
    private Map<String, String> immutableQuoteSnapshot(Quotation item) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("fipeValue", String.valueOf(item.getFipeValue()));
        snapshot.put("categoryCode", String.valueOf(item.getCategoryCode()));
        snapshot.put("region", String.valueOf(item.getRegion()));
        snapshot.put("motorcycleOrigin", String.valueOf(item.getMotorcycleOrigin()));
        snapshot.put("motorcycleCc", String.valueOf(item.getMotorcycleCc()));
        snapshot.put("selectedPlanCode", String.valueOf(item.getSelectedPlanCode()));
        snapshot.put("selectedPlanName", String.valueOf(item.getSelectedPlanName()));
        snapshot.put("baseMonthlyValue", String.valueOf(item.getBaseMonthlyValue()));
        snapshot.put("preDiscountMonthlyValue", String.valueOf(item.getPreDiscountMonthlyValue()));
        snapshot.put("discountPercent", String.valueOf(item.getDiscountPercent()));
        snapshot.put("rearWindowBranding", String.valueOf(item.getRearWindowBranding()));
        snapshot.put("monthlyValue", String.valueOf(item.getMonthlyValue()));
        snapshot.put("mandatoryMonthlyFee", String.valueOf(item.getMandatoryMonthlyFee()));
        snapshot.put("oneTimeFee", String.valueOf(item.getOneTimeFee()));
        snapshot.put("mandatoryFeeDescription", String.valueOf(item.getMandatoryFeeDescription()));
        for (int index = 0; index < item.getSelectedOptionals().size(); index++) {
            var optional = item.getSelectedOptionals().get(index);
            snapshot.put("optional." + index, optional.getCoverageCode() + "|" + optional.getMonthlyPrice());
        }
        int index = 0;
        for (var coverage : item.getCoverageSnapshots()) {
            snapshot.put("coverage." + index++, coverage.getCoverageCode() + "|"
                    + coverage.getCoverageStatus() + "|" + coverage.getMonthlyPrice());
        }
        return snapshot;
    }

    @Transactional
    public ConsultantInspectionSummary markCompletionMessageSent(UUID inspectionId) {
        InspectionRequest inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        inspection.markCompletionMessageSent();
        inspectionRepository.flush();
        return toInspection(inspection);
    }

    private ConsultantInspectionSummary toInspection(InspectionRequest item) {
        InspectionResponse response = retratoService.toResponse(item);
        String completionUrl = associateCompletionWhatsappUrl(item);
        boolean pending = completionUrl != null
                && item.getCompletionMessageSentAt() == null
                && item.getCompletedAt() != null
                && (item.getStatus() == InspectionRequestStatus.COMPLETED
                    || item.getStatus() == InspectionRequestStatus.UNDER_REVIEW);
        List<br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse> assets = item.getAssets().stream()
                .map(asset -> new br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse(
                        asset.getId(), asset.getAssetType(), asset.getLabel(), asset.getFileName(),
                        asset.getContentType(), asset.getFileSize(), null, asset.getSortOrder(),
                        storageService.isAvailable(asset), asset.getStoredAt(), asset.getExpiresAt(), asset.getPurgedAt()
                ))
                .toList();
        int availableCount = (int) assets.stream()
                .filter(br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse::available)
                .count();
        OffsetDateTime filesExpireAt = assets.stream()
                .filter(br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse::available)
                .map(br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse::expiresAt)
                .filter(java.util.Objects::nonNull)
                .max(OffsetDateTime::compareTo)
                .orElse(null);
        boolean expiredWithoutFiles = !item.hasAnyPreservedFile()
                && ((item.getExpiresAt() != null && OffsetDateTime.now().isAfter(item.getExpiresAt()))
                    || (item.getQuotation() != null && item.getQuotation().getValidUntil() != null
                        && OffsetDateTime.now().isAfter(item.getQuotation().getValidUntil())));
        return new ConsultantInspectionSummary(
                item.getId(), item.getRequestType(), item.getAssociateName(), item.getPlate(), displayStatus(item),
                item.getCreatedAt(), item.getExpiresAt(), expiredWithoutFiles, item.getCompletedAt(), null,
                item.getWhatsapp(), completionUrl, item.getCompletionMessageSentAt(), pending,
                response.publicUrl(), response.whatsappUrl(), null, availableCount > 0, availableCount,
                filesExpireAt, assets
        );
    }

    private InspectionRequestStatus displayStatus(InspectionRequest item) {
        if (item == null) return null;
        long availableCount = item.getAssets().stream().filter(storageService::isAvailable).count();
        if (availableCount == 0
                && item.getStatus() != InspectionRequestStatus.CANCELLED
                && item.getStatus() != InspectionRequestStatus.EXPIRED) {
            return InspectionRequestStatus.WAITING_FILES;
        }
        if (availableCount > 0
                && (item.getStatus() == InspectionRequestStatus.WAITING_FILES
                    || item.getStatus() == InspectionRequestStatus.CREATED)) {
            return InspectionRequestStatus.UPLOADING_FILES;
        }
        return item.getStatus();
    }

    private boolean hasFiles(InspectionRequest item) {
        return item != null && item.hasAnyPreservedFile();
    }

    private String associateCompletionWhatsappUrl(InspectionRequest item) {
        String phone = normalizePhone(item.getWhatsapp());
        if (phone == null) return null;
        String firstName = item.getAssociateName() == null || item.getAssociateName().isBlank()
                ? "associado" : item.getAssociateName().trim().split("\\s+")[0];
        String message = "Olá, " + firstName
                + "! Sua vistoria foi realizada com sucesso. Aguarde a análise da equipe " + TenantIdentity.legalName() + ".";
        return "https://wa.me/" + phone + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    private String normalizePhone(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 10 || digits.length() == 11) digits = "55" + digits;
        return digits.matches("^[1-9][0-9]{11,14}$") ? digits : null;
    }
}
