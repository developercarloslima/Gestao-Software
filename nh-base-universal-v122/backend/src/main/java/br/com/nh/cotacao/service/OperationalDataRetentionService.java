package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.QuotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * V83.35: não existe mais retenção geral por 40 dias.
 *
 * A limpeza automática remove somente cotações cujo prazo comercial venceu e que
 * não possuem nenhum arquivo de vistoria preservado. Isso abrange tanto cotação
 * sem vistoria iniciada quanto vistoria criada, porém ainda sem qualquer arquivo.
 * Se houver ao menos um arquivo válido, cotação/vistoria permanecem preservadas.
 */
@Service
public class OperationalDataRetentionService {
    private static final Logger log = LoggerFactory.getLogger(OperationalDataRetentionService.class);

    private final InspectionRequestRepository inspectionRepository;
    private final QuotationRepository quotationRepository;
    private final CatalogChangeAuditRepository auditRepository;

    public OperationalDataRetentionService(
            InspectionRequestRepository inspectionRepository,
            QuotationRepository quotationRepository,
            CatalogChangeAuditRepository auditRepository
    ) {
        this.inspectionRepository = inspectionRepository;
        this.quotationRepository = quotationRepository;
        this.auditRepository = auditRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${app.expired-quote-cleanup.cleanup-cron:0 */15 * * * *}")
    @Transactional
    public void cleanupExpiredOperationalData() {
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> expiredIds = quotationRepository.findExpiredWithoutPreservedInspectionFiles(now);
        if (expiredIds.isEmpty()) return;

        int deletedInspections = inspectionRepository.deleteByQuotationIds(expiredIds);
        quotationRepository.deleteAllByIdInBatch(expiredIds);

        String summary = "cotações vencidas sem arquivos=" + expiredIds.size()
                + "; vistorias sem arquivos removidas=" + deletedInspections
                + "; execução=" + now;
        auditRepository.save(CatalogChangeAudit.createText(
                "EXPIRED_UNUSED_QUOTES", null, "AUTO",
                "Limpeza automática de cotações vencidas sem vistoria/arquivos",
                null, summary, "SYSTEM"
        ));
        log.info("Limpeza de cotações vencidas sem arquivos executada: {}", summary);
    }
}
