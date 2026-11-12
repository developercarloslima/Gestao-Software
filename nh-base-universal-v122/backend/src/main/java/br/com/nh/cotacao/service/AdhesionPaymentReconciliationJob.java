package br.com.nh.cotacao.service;

import br.com.nh.cotacao.repository.FinancialChargeRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fallback do webhook: reconsulta apenas adesões com cobrança Asaas já criada.
 * A rotina nunca emite uma cobrança, nunca simula pagamento e usa o mesmo
 * caminho transacional que a sincronização manual do Financeiro.
 */
@Component
public class AdhesionPaymentReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(AdhesionPaymentReconciliationJob.class);
    private final FinancialChargeRepository charges;
    private final CommercialFinanceService finance;
    private final FinancialProviderGateway provider;

    public AdhesionPaymentReconciliationJob(FinancialChargeRepository charges,
            CommercialFinanceService finance, FinancialProviderGateway provider) {
        this.charges = charges;
        this.finance = finance;
        this.provider = provider;
    }

    @Scheduled(fixedDelayString="${app.financial.adhesion-sync-interval-ms:300000}",
               initialDelayString="${app.financial.adhesion-sync-initial-delay-ms:60000}")
    public void synchronizeAdhesions() {
        if (!provider.enabled()) return;
        int checked = 0, failed = 0;
        for (UUID chargeId : charges.findAdhesionChargesNeedingReconciliation()) {
            try {
                // Proxy Spring do serviço garante que cada cobrança seja conciliada
                // isoladamente e com commit próprio.
                finance.refreshCharge(chargeId, null, PortalRole.ADMIN);
                checked++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Reconsulta de adesão falhou ({}).", ex.getClass().getSimpleName());
            }
        }
        if (checked > 0 || failed > 0)
            log.info("Conciliação periódica de adesões: verificadas={}, falhas={}.", checked, failed);
    }
}
