package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.InspectionAsset;
import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestType;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class InspectionReportDownloadService {
    private static final Logger log = LoggerFactory.getLogger(InspectionReportDownloadService.class);

    private final InspectionRequestRepository inspectionRepository;
    private final RetratoPdfService pdfService;
    private final InspectionAssetStorageService storageService;
    private final SiteDocumentService siteDocumentService;

    public InspectionReportDownloadService(
            InspectionRequestRepository inspectionRepository,
            RetratoPdfService pdfService,
            InspectionAssetStorageService storageService,
            SiteDocumentService siteDocumentService
    ) {
        this.inspectionRepository = inspectionRepository;
        this.pdfService = pdfService;
        this.storageService = storageService;
        this.siteDocumentService = siteDocumentService;
    }

    @Transactional
    public byte[] generate(UUID inspectionId) {
        InspectionRequest request = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria não encontrada."));

        Optional<InspectionAsset> storedReport = request.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                .filter(storageService::isAvailable)
                .max((a, b) -> {
                    if (a.getStoredAt() == null && b.getStoredAt() == null) return 0;
                    if (a.getStoredAt() == null) return -1;
                    if (b.getStoredAt() == null) return 1;
                    return a.getStoredAt().compareTo(b.getStoredAt());
                });

        byte[] storedBytes = null;
        if (storedReport.isPresent()) {
            try {
                storedBytes = storageService.readAll(storedReport.get().getId());
            } catch (Exception readException) {
                log.warn("Não foi possível ler o relatório já armazenado da vistoria {}. Será tentada uma nova geração.", inspectionId, readException);
            }
        }

        try {
            // Não troca o arquivo no meio de uma cerimônia WebAuthn já iniciada, pois o
            // hash do dossiê faz parte da evidência criptográfica em andamento. Depois do
            // aceite o próprio fluxo gera novamente o PDF no layout vigente.
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
            boolean digitalAcceptanceInProgress = request.getAcceptedAt() == null
                    && request.getAcceptanceEvidenceHash() != null
                    && !request.getAcceptanceEvidenceHash().isBlank()
                    && ((request.getWebauthnRegistrationExpiresAt() != null && request.getWebauthnRegistrationExpiresAt().isAfter(now))
                        || (request.getWebauthnAssertionExpiresAt() != null && request.getWebauthnAssertionExpiresAt().isAfter(now)));
            // Depois do aceite digital o dossiê é imutável: entregamos exatamente
            // o arquivo preservado que representa a versão aceita pelo associado.
            if (request.getAcceptedAt() != null && pdfService.isUsableDownloadPdf(storedBytes)) {
                return storedBytes;
            }

            if (digitalAcceptanceInProgress && pdfService.isUsableDownloadPdf(storedBytes)) {
                return storedBytes;
            }

            // Antes do aceite, um PDF só pode ser reutilizado se estiver no layout
            // atual E tiver sido gravado depois das últimas alterações da vistoria
            // e da cotação. Isso elimina dossiê antigo após correção cadastral/comercial.
            if (storedBytes != null
                    && pdfService.isCurrentLayout(storedBytes)
                    && storedReport.isPresent()
                    && isReportFresh(request, storedReport.get())) {
                return storedBytes;
            }

            boolean sourceAssetUnavailable = request.getAssets().stream()
                    .filter(asset -> asset.getAssetType() != InspectionAssetType.REPORT)
                    .anyMatch(asset -> !storageService.isAvailable(asset));

            byte[] standardized;
            if (!sourceAssetUnavailable) {
                standardized = pdfService.generate(request);
            } else if (storedBytes != null) {
                standardized = pdfService.standardizeLegacyReport(request, storedBytes);
            } else {
                standardized = pdfService.generate(request);
            }

            int reportOrder = storedReport.map(InspectionAsset::getSortOrder)
                    .orElseGet(() -> defaultReportOrder(request));
            storageService.replaceGeneratedReport(
                    request,
                    "Dossiê padronizado da vistoria",
                    "dossie-padronizado-vistoria-" + request.getId() + ".pdf",
                    reportOrder,
                    standardized
            );
            inspectionRepository.flush();
            return standardized;
        } catch (Exception generationException) {
            // Um relatório já consolidado e legível é melhor do que interromper o download
            // no celular. Se a atualização do layout falhar por memória, arquivo legado ou
            // qualquer incompatibilidade, entregamos a última cópia válida preservada.
            if (pdfService.isUsableDownloadPdf(storedBytes)) {
                log.warn("Falha ao regenerar o relatório da vistoria {}. Entregando a última cópia válida preservada.", inspectionId, generationException);
                return storedBytes;
            }
            throw new IllegalStateException(
                    "Não foi possível gerar o relatório desta vistoria. Os arquivos preservados não foram alterados.",
                    generationException
            );
        }
    }


    private boolean isReportFresh(InspectionRequest request, InspectionAsset report) {
        if (report == null || report.getStoredAt() == null) return false;
        java.time.OffsetDateTime latestChange = request.getUpdatedAt();
        if (request.getQuotation() != null && request.getQuotation().getUpdatedAt() != null
                && (latestChange == null || request.getQuotation().getUpdatedAt().isAfter(latestChange))) {
            latestChange = request.getQuotation().getUpdatedAt();
        }
        // Capa e página final são configuração global do relatório. A revisão
        // considera tanto upload/substituição quanto remoção, pois todas as ações
        // são auditadas em catalog_change_audit.
        java.time.OffsetDateTime decorationRevision = siteDocumentService.reportDecorationRevisionAt();
        if (decorationRevision != null && (latestChange == null || decorationRevision.isAfter(latestChange))) {
            latestChange = decorationRevision;
        }
        return latestChange == null || !report.getStoredAt().isBefore(latestChange);
    }

    private int defaultReportOrder(InspectionRequest request) {
        return request.getRequestType() == InspectionRequestType.NEW_INSPECTION
                ? request.getVehicleType().requiredPhotoCount() + 6
                : 2;
    }
}
