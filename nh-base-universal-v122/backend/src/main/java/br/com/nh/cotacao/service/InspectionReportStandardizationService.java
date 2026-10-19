package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class InspectionReportStandardizationService {
    private static final Logger log = LoggerFactory.getLogger(InspectionReportStandardizationService.class);

    private final InspectionRequestRepository inspectionRepository;
    private final InspectionReportDownloadService reportDownloadService;
    private final RetratoPdfService pdfService;
    private final InspectionAssetStorageService storageService;
    private final boolean enabled;

    public InspectionReportStandardizationService(
            InspectionRequestRepository inspectionRepository,
            InspectionReportDownloadService reportDownloadService,
            RetratoPdfService pdfService,
            InspectionAssetStorageService storageService,
            @Value("${app.inspection-report.standardize-existing:true}") boolean enabled
    ) {
        this.inspectionRepository = inspectionRepository;
        this.reportDownloadService = reportDownloadService;
        this.pdfService = pdfService;
        this.storageService = storageService;
        this.enabled = enabled;
    }

    /**
     * Após o deploy, normaliza automaticamente PDFs já existentes. O mesmo processo
     * roda novamente de tempos em tempos, mas ignora relatórios que já carregam a
     * versão atual do layout. Assim o acervo pronto para download converge para um
     * único padrão sem exigir ação manual no painel.
     */
    @Scheduled(
            initialDelayString = "${app.inspection-report.standardize-initial-delay-ms:45000}",
            fixedDelayString = "${app.inspection-report.standardize-fixed-delay-ms:21600000}"
    )
    public void standardizeExistingReports() {
        if (!enabled) return;

        int checked = 0;
        int upgraded = 0;
        int failed = 0;

        for (var inspection : inspectionRepository.findAllByOrderByCreatedAtDesc()) {
            var report = inspection.getAssets().stream()
                    .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                    .filter(storageService::isAvailable)
                    .findFirst();
            if (report.isEmpty()) continue;
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
            boolean webauthnCeremonyActive = inspection.getAcceptedAt() == null
                    && inspection.getAcceptanceEvidenceHash() != null
                    && !inspection.getAcceptanceEvidenceHash().isBlank()
                    && ((inspection.getWebauthnRegistrationExpiresAt() != null && inspection.getWebauthnRegistrationExpiresAt().isAfter(now))
                        || (inspection.getWebauthnAssertionExpiresAt() != null && inspection.getWebauthnAssertionExpiresAt().isAfter(now)));
            if (webauthnCeremonyActive) continue;

            checked++;
            try {
                byte[] current = storageService.readAll(report.get().getId());
                if (pdfService.isCurrentLayout(current)) continue;
                reportDownloadService.generate(inspection.getId());
                upgraded++;
            } catch (Exception exception) {
                failed++;
                log.warn("Não foi possível padronizar automaticamente o relatório da vistoria {}: {}",
                        inspection.getId(), exception.getMessage());
            }
        }

        if (checked > 0 || upgraded > 0 || failed > 0) {
            log.info("Padronização de PDFs concluída: verificados={}, atualizados={}, falhas={}", checked, upgraded, failed);
        }
    }
}
