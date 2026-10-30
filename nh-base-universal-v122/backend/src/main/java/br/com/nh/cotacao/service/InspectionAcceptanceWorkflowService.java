package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.InspectionRequest;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class InspectionAcceptanceWorkflowService {
    private final SecureRandom secureRandom = new SecureRandom();
    private String securePublicToken() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private final JdbcTemplate jdbc;
    private final InspectionRequestRepository inspections;
    private final CatalogChangeAuditRepository audits;
    private final PortalUserService portalUsers;

    public InspectionAcceptanceWorkflowService(JdbcTemplate jdbc, InspectionRequestRepository inspections,
            CatalogChangeAuditRepository audits, PortalUserService portalUsers) {
        this.jdbc = jdbc;
        this.inspections = inspections;
        this.audits = audits;
        this.portalUsers = portalUsers;
    }

    public record Progress(boolean dossier, boolean responsabilidade, boolean cienciaPecas,
                           boolean allAccepted, boolean expired, int completed, int total) {}

    private record Term(OffsetDateTime availableAt, OffsetDateTime signedAt) {}

    private Term latest(String table, UUID id) {
        // Table is exclusively one of two code-owned constants; never user input.
        return jdbc.query("select coalesce(link_available_at,created_at),accepted_at from " + table
                        + " where inspection_id=? order by version desc limit 1",
                rs -> rs.next() ? new Term(rs.getObject(1, OffsetDateTime.class),
                                           rs.getObject(2, OffsetDateTime.class)) : null, id);
    }

    @Transactional(readOnly = true)
    public Progress progress(InspectionRequest inspection) {
        Term responsabilidade = latest("inspection_responsibility_terms", inspection.getId());
        Term pecas = latest("inspection_parts_delay_terms", inspection.getId());
        boolean dossier = inspection.getAcceptedAt() != null;
        boolean signedResponsabilidade = responsabilidade != null && responsabilidade.signedAt() != null;
        boolean signedPecas = pecas != null && pecas.signedAt() != null;
        int done = (dossier ? 1 : 0) + (signedResponsabilidade ? 1 : 0) + (signedPecas ? 1 : 0);
        OffsetDateTime now = OffsetDateTime.now();
        boolean expired = (!dossier && inspection.getAcceptanceLinkAvailableAt() != null
                             && !AcceptanceLinkPolicy.isValid(inspection.getAcceptanceLinkAvailableAt(), now))
                || (responsabilidade != null && !signedResponsabilidade
                             && !AcceptanceLinkPolicy.isValid(responsabilidade.availableAt(), now))
                || (pecas != null && !signedPecas
                             && !AcceptanceLinkPolicy.isValid(pecas.availableAt(), now));
        return new Progress(dossier, signedResponsabilidade, signedPecas, done == 3, expired, done, 3);
    }

    @Transactional
    public Progress renewAfterApproval(UUID inspectionId, String note, String username, PortalRole role) {
        if (role != PortalRole.ADMIN && role != PortalRole.SUPERVISION_ANALYSIS)
            throw new IllegalArgumentException("Somente ADM ou Supervisão pode reaprovar links expirados.");
        if (note == null || note.isBlank() || note.trim().length() < 5)
            throw new IllegalArgumentException("Informe o motivo da reaprovação (mínimo 5 caracteres).");
        if (role == PortalRole.SUPERVISION_ANALYSIS)
            portalUsers.assertSupervisionInspectionAccess(username, role, inspectionId);
        InspectionRequest inspection = inspections.findForAcceptanceReapproval(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria não encontrada."));
        if (inspection.getStatus() != InspectionRequestStatus.APPROVED
                || inspection.getRegistrationCompletedAt() == null)
            throw new IllegalArgumentException("A Supervisão precisa aprovar a vistoria com cadastro concluído.");
        Progress before = progress(inspection);
        if (before.allAccepted()) throw new IllegalArgumentException("Os três aceites já estão concluídos.");
        if (!before.expired()) throw new IllegalArgumentException("O prazo de 7 dias ainda está válido; não é permitido renová-lo.");
        // Renovar o fluxo abre nova análise da fila, sem fabricar assinatura.
        inspection.clearManualAcceptanceQueueMove();
        inspections.saveAndFlush(inspection);
        OffsetDateTime now = OffsetDateTime.now();
        if (inspection.getAcceptedAt() == null) {
            inspection.renewPendingAcceptance(securePublicToken(), now);
            inspections.saveAndFlush(inspection);
        }
        renewTerm("inspection_responsibility_terms", inspectionId, now);
        renewTerm("inspection_parts_delay_terms", inspectionId, now);
        audits.save(CatalogChangeAudit.createText("INSPECTION_ACCEPTANCE_REAPPROVAL", null,
                inspectionId.toString(), "Supervisão renovou os links ainda pendentes por 7 dias",
                "Aceites confirmados: " + before.completed() + "/3; prazo expirado",
                "Novo prazo autorizado; motivo: " + note.trim(), username));
        return progress(inspection);
    }

    private void renewTerm(String table, UUID inspectionId, OffsetDateTime now) {
        jdbc.update("""
            update %s set public_token=?,link_available_at=?,whatsapp_sent_to=null,sent_at=null,
                webauthn_registration_challenge=null,webauthn_registration_expires_at=null,
                webauthn_origin=null,webauthn_rp_id=null,webauthn_credential_id=null,
                webauthn_public_key=null,webauthn_algorithm=null,webauthn_sign_count=0,
                webauthn_assertion_challenge=null,webauthn_assertion_expires_at=null,
                acceptance_evidence_hash=null,term_pdf_sha256=null,term_content_sha256=null,
                acceptance_assertion_signature=null,acceptance_authenticator_data=null,
                acceptance_client_data_json=null,acceptance_proof_hash=null,
                acceptance_user_verified=null,acceptance_device_metadata=null,
                acceptance_device_id=null,acceptance_ip=null,acceptance_latitude=null,
                acceptance_longitude=null,acceptance_accuracy_meters=null,
                acceptance_location_captured_at=null,updated_at=now()
            where id=(select id from %s where inspection_id=? order by version desc limit 1)
              and accepted_at is null
            """.formatted(table, table), securePublicToken(), now, inspectionId);
    }
}
