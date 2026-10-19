package br.com.nh.cotacao.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class InspectionAssetRetentionService {
    private static final Logger log = LoggerFactory.getLogger(InspectionAssetRetentionService.class);
    private final InspectionAssetStorageService storageService;

    public InspectionAssetRetentionService(InspectionAssetStorageService storageService) {
        this.storageService = storageService;
    }

    @Scheduled(cron = "${app.inspection-storage.cleanup-cron:0 15 * * * *}")
    public void purgeExpiredAssets() {
        int removed = storageService.purgeExpired();
        if (removed > 0) {
            log.info("Retrato NH: {} upload(s) temporário(s) abandonado(s) removido(s) do PostgreSQL.", removed);
        }
    }
}
