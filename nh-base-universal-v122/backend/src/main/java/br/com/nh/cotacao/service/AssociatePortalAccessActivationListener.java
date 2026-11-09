package br.com.nh.cotacao.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Ativa o portal somente após o commit do aceite WebAuthn.
 * Uma falha de provisionamento jamais pode desfazer a assinatura digital legítima.
 */
@Service
public class AssociatePortalAccessActivationListener {
    private static final Logger log = LoggerFactory.getLogger(AssociatePortalAccessActivationListener.class);
    private final InspectionAcceptanceWorkflowService workflow;
    private final br.com.nh.cotacao.repository.InspectionRequestRepository inspections;
    private final CommercialFinanceService commercialFinance;

    public AssociatePortalAccessActivationListener(InspectionAcceptanceWorkflowService workflow,
            br.com.nh.cotacao.repository.InspectionRequestRepository inspections,
            CommercialFinanceService commercialFinance) {
        this.workflow = workflow;
        this.inspections = inspections;
        this.commercialFinance = commercialFinance;
    }

    public record AcceptanceRecorded(UUID inspectionId) {}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void activateAfterCommit(AcceptanceRecorded event) {
        if (event == null || event.inspectionId() == null) return;
        try {
            workflow.activateAssociatePortalAccessIfComplete(event.inspectionId());
            inspections.findById(event.inspectionId()).ifPresent(inspection -> {
                if (inspection.getQuotation() != null) {
                    commercialFinance.reconcileAcceptedInspection(inspection.getQuotation().getId());
                }
            });
        } catch (RuntimeException error) {
            log.error("Aceite digital confirmado, mas não foi possível ativar o portal do associado para a vistoria {}. "
                    + "O aceite permanece preservado; o provisionamento precisa ser reprocessado.",
                    event.inspectionId(), error);
        }
    }
}
