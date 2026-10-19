package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.PortalDtos.ConsultantResponse;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConsultantService {
    public static final int MAX_CONSULTANTS_PER_ANALYST = 30;

    private final ConsultantRepository repository;
    private final QuotationRepository quotationRepository;
    private final InspectionRequestRepository inspectionRepository;
    private final CatalogChangeAuditRepository auditRepository;
    private final PortalUserRepository portalUserRepository;

    public ConsultantService(
            ConsultantRepository repository,
            QuotationRepository quotationRepository,
            InspectionRequestRepository inspectionRepository,
            CatalogChangeAuditRepository auditRepository,
            PortalUserRepository portalUserRepository
    ) {
        this.repository = repository;
        this.quotationRepository = quotationRepository;
        this.inspectionRepository = inspectionRepository;
        this.auditRepository = auditRepository;
        this.portalUserRepository = portalUserRepository;
    }

    @Transactional(readOnly = true)
    public List<ConsultantResponse> active() {
        return repository.findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole.CONSULTANT)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ConsultantResponse> activeAnalysts() {
        return repository.findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole.ANALYST)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ConsultantResponse> activeSupervisors() {
        return repository.findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole.SUPERVISION_ANALYSIS)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<ConsultantResponse> all() {
        return repository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ConsultantResponse active(UUID id) { return toResponse(findActiveConsultant(id)); }

    @Transactional
    public ConsultantResponse registerPortalLogin(UUID id) {
        Consultant consultant = findActiveConsultant(id);
        consultant.registerPortalLogin();
        return toResponse(repository.save(consultant));
    }

    @Transactional(readOnly = true)
    public Optional<Consultant> findMostRecentPortalConsultant() {
        return repository.findFirstByActiveTrueAndRoleAndLastPortalLoginAtIsNotNullOrderByLastPortalLoginAtDesc(
                CollaboratorRole.CONSULTANT
        );
    }

    @Transactional
    public ConsultantResponse create(String name, String source) {
        return create(name, CollaboratorRole.CONSULTANT, null, null, null, source, source);
    }

    @Transactional
    public ConsultantResponse create(String name, String source, String username) {
        return create(name, CollaboratorRole.CONSULTANT, null, null, null, source, username);
    }

    @Transactional
    public ConsultantResponse create(String name, CollaboratorRole role, String source, String username) {
        return create(name, role, null, null, null, source, username);
    }

    @Transactional
    public ConsultantResponse create(String name, CollaboratorRole role, String whatsapp, String source, String username) {
        return create(name, role, whatsapp, null, null, source, username);
    }

    @Transactional
    public ConsultantResponse create(
            String name,
            CollaboratorRole role,
            String whatsapp,
            String city,
            UUID assignedAnalystId,
            String source,
            String username
    ) {
        CollaboratorRole requestedRole = role == null ? CollaboratorRole.CONSULTANT : role;
        String normalized = Consultant.normalize(name);
        Consultant collaborator = repository.findByNormalizedName(normalized)
                .map(existing -> {
                    if (existing.getRole() != requestedRole) {
                        throw new IllegalArgumentException(
                                "Já existe um colaborador com esse nome cadastrado como " + roleLabel(existing.getRole()) + "."
                        );
                    }
                    if (!existing.isActive()) {
                        ensureAnalystCapacityForActivation(existing);
                        existing.setActive(true);
                    }
                    return existing;
                })
                .orElseGet(() -> Consultant.create(name, source, requestedRole));

        if (whatsapp != null) collaborator.setWhatsapp(whatsapp);
        if (city != null) collaborator.setCity(city);
        if (requestedRole != CollaboratorRole.CONSULTANT) {
            collaborator.assignAnalyst(null);
        } else if (assignedAnalystId != null) {
            applyAnalystAssignment(collaborator, requestedRole, assignedAnalystId);
        }

        Consultant saved = repository.saveAndFlush(collaborator);
        syncOpenInspectionAssignments(saved);
        auditRepository.save(CatalogChangeAudit.createText(
                "COLLABORATOR", null, saved.getId().toString(),
                "Colaborador cadastrado/reativado — " + saved.getName(),
                null, collaboratorSummary(saved), username
        ));
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID id, String username) {
        Consultant collaborator = findActiveOrInactive(id);
        portalUserRepository.findByConsultantId(id).ifPresent(user -> {
            throw new IllegalArgumentException(
                    "Este colaborador possui o usuário " + user.getUsername()
                            + " vinculado. Altere ou exclua a conta na aba Usuários antes de remover o colaborador."
            );
        });
        if (collaborator.getRole() == CollaboratorRole.ANALYST
                && repository.countByAssignedAnalyst_IdAndActiveTrue(id) > 0) {
            throw new IllegalArgumentException("Este analista ainda possui consultores vinculados. Redistribua a equipe antes de excluí-lo.");
        }
        String old = collaboratorSummary(collaborator)
                + "; cotações=" + quotationRepository.countByConsultantId(id)
                + "; vistorias=" + inspectionRepository.countByConsultantId(id);
        String name = collaborator.getName();
        repository.delete(collaborator);
        auditRepository.save(CatalogChangeAudit.createText(
                "COLLABORATOR", null, id.toString(), "Colaborador excluído — " + name,
                old, "Cadastro removido; atividades históricas mantidas com o nome original.", username
        ));
    }

    @Transactional
    public ConsultantResponse update(UUID id, String name, Boolean active, CollaboratorRole role, String username) {
        return update(id, name, active, role, null, null, null, username);
    }

    @Transactional
    public ConsultantResponse update(UUID id, String name, Boolean active, CollaboratorRole role, String whatsapp, String username) {
        return update(id, name, active, role, whatsapp, null, null, username);
    }

    @Transactional
    public ConsultantResponse update(
            UUID id,
            String name,
            Boolean active,
            CollaboratorRole role,
            String whatsapp,
            String city,
            UUID assignedAnalystId,
            String username
    ) {
        Consultant collaborator = findActiveOrInactive(id);
        String old = collaboratorSummary(collaborator);

        if (name != null && !name.isBlank() && !Consultant.normalize(name).equals(collaborator.getNormalizedName())) {
            repository.findByNormalizedName(Consultant.normalize(name)).ifPresent(existing -> {
                if (!existing.getId().equals(id)) throw new IllegalArgumentException("Já existe um colaborador com esse nome.");
            });
            collaborator.rename(name);
        }

        CollaboratorRole nextRole = role == null ? collaborator.getRole() : role;
        if (role != null && role != collaborator.getRole()) {
            if (collaborator.getRole() == CollaboratorRole.ANALYST
                    && repository.countByAssignedAnalyst_IdAndActiveTrue(id) > 0) {
                throw new IllegalArgumentException("Redistribua os consultores deste analista antes de alterar o cargo.");
            }
            portalUserRepository.findByConsultantId(id).ifPresent(user -> {
                if (!portalRoleMatchesCollaborator(role, user.getRole())) {
                    throw new IllegalArgumentException(
                            "Este colaborador possui um usuário vinculado com outro tipo. Ajuste primeiro o tipo do usuário na aba Usuários."
                    );
                }
            });
            collaborator.setRole(role);
        }

        if (active != null && !active && collaborator.getRole() == CollaboratorRole.ANALYST
                && repository.countByAssignedAnalyst_IdAndActiveTrue(id) > 0) {
            throw new IllegalArgumentException("Redistribua os consultores deste analista antes de desativá-lo.");
        }
        if (Boolean.TRUE.equals(active) && !collaborator.isActive()) {
            ensureAnalystCapacityForActivation(collaborator);
        }
        if (active != null) collaborator.setActive(active);
        if (whatsapp != null) collaborator.setWhatsapp(whatsapp);
        if (city != null) collaborator.setCity(city);
        if (nextRole != CollaboratorRole.CONSULTANT) {
            collaborator.assignAnalyst(null);
        } else if (assignedAnalystId != null) {
            applyAnalystAssignment(collaborator, nextRole, assignedAnalystId);
        } else if (role != null || name != null || city != null) {
            // Edição completa pelo Admin: deixar o campo vazio significa remover o vínculo.
            collaborator.assignAnalyst(null);
        }

        Consultant saved = repository.saveAndFlush(collaborator);
        syncOpenInspectionAssignments(saved);
        auditRepository.save(CatalogChangeAudit.createText(
                "COLLABORATOR", null, id.toString(), "Colaborador alterado — " + saved.getName(),
                old, collaboratorSummary(saved), username
        ));
        return toResponse(saved);
    }

    @Transactional
    public ConsultantResponse update(UUID id, String name, Boolean active, String username) {
        return update(id, name, active, null, null, null, null, username);
    }

    private void ensureAnalystCapacityForActivation(Consultant collaborator) {
        if (collaborator.getRole() != CollaboratorRole.CONSULTANT || collaborator.getAssignedAnalyst() == null) return;
        UUID analystId = collaborator.getAssignedAnalyst().getId();
        long activeCount = repository.countByAssignedAnalyst_IdAndActiveTrue(analystId);
        if (activeCount >= MAX_CONSULTANTS_PER_ANALYST) {
            throw new IllegalArgumentException(
                    "O analista " + collaborator.getAssignedAnalyst().getName()
                            + " já possui " + MAX_CONSULTANTS_PER_ANALYST
                            + " consultores ativos. Redistribua a equipe antes de reativar este consultor."
            );
        }
    }

    private void applyAnalystAssignment(Consultant collaborator, CollaboratorRole role, UUID assignedAnalystId) {
        if (role != CollaboratorRole.CONSULTANT) {
            collaborator.assignAnalyst(null);
            return;
        }
        if (assignedAnalystId == null) {
            collaborator.assignAnalyst(null);
            return;
        }
        Consultant analyst = findActiveAnalyst(assignedAnalystId);
        long currentCount = repository.countByAssignedAnalyst_IdAndActiveTrue(assignedAnalystId);
        boolean alreadyAssigned = collaborator.getAssignedAnalyst() != null
                && analyst.getId().equals(collaborator.getAssignedAnalyst().getId());
        if (!alreadyAssigned && currentCount >= MAX_CONSULTANTS_PER_ANALYST) {
            throw new IllegalArgumentException(
                    "Este analista já possui " + MAX_CONSULTANTS_PER_ANALYST + " consultores vinculados. Selecione outro analista."
            );
        }
        collaborator.assignAnalyst(analyst);
    }

    private void syncOpenInspectionAssignments(Consultant collaborator) {
        if (collaborator.getRole() != CollaboratorRole.CONSULTANT) return;

        // A troca de analista de um consultor deve afetar somente vistorias que ainda
        // estão efetivamente na fila dos analistas. Cotações/vendas e vistorias já
        // concluídas permanecem como histórico e não participam da transferência.
        // O update em lote também evita carregar anexos/relatórios antigos durante a
        // simples edição do colaborador, o que causava erro 500 em consultores com
        // histórico grande de atividades.
        Consultant analyst = collaborator.getAssignedAnalyst();
        inspectionRepository.reassignPendingAnalysisForConsultant(
                collaborator.getId(),
                analyst,
                analyst == null ? null : analyst.getName(),
                List.of(InspectionAnalysisStage.ANALYST_QUEUE, InspectionAnalysisStage.ANALYST_PENDING),
                List.of(
                        InspectionRequestStatus.APPROVED,
                        InspectionRequestStatus.REJECTED,
                        InspectionRequestStatus.CANCELLED,
                        InspectionRequestStatus.EXPIRED
                )
        );
    }

    private boolean portalRoleMatchesCollaborator(CollaboratorRole collaboratorRole, br.com.nh.cotacao.security.PortalRole portalRole) {
        return switch (collaboratorRole) {
            case CONSULTANT -> portalRole == br.com.nh.cotacao.security.PortalRole.CONSULTANT;
            case ANALYST -> portalRole == br.com.nh.cotacao.security.PortalRole.ANALYST;
            case SUPERVISION_ANALYSIS -> portalRole == br.com.nh.cotacao.security.PortalRole.SUPERVISION_ANALYSIS;
        };
    }

    @Transactional(readOnly = true)
    public Consultant findActive(UUID id) { return findActiveConsultant(id); }

    @Transactional(readOnly = true)
    public Consultant findActiveConsultant(UUID id) { return findActiveWithRole(id, CollaboratorRole.CONSULTANT); }

    @Transactional(readOnly = true)
    public Consultant findActiveAnalyst(UUID id) { return findActiveWithRole(id, CollaboratorRole.ANALYST); }

    @Transactional(readOnly = true)
    public Consultant findActiveSupervisor(UUID id) { return findActiveWithRole(id, CollaboratorRole.SUPERVISION_ANALYSIS); }

    @Transactional(readOnly = true)
    public Consultant findActiveWithRole(UUID id, CollaboratorRole role) {
        Consultant collaborator = findActiveOrInactive(id);
        if (!collaborator.isActive()) throw new IllegalArgumentException("O colaborador selecionado está inativo.");
        if (collaborator.getRole() != role) {
            throw new IllegalArgumentException("O colaborador selecionado não possui o cargo " + roleLabel(role) + ".");
        }
        return collaborator;
    }

    private Consultant findActiveOrInactive(UUID id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Colaborador não encontrado."));
    }

    private ConsultantResponse toResponse(Consultant collaborator) {
        Consultant analyst = collaborator.getAssignedAnalyst();
        return new ConsultantResponse(
                collaborator.getId(), collaborator.getName(), collaborator.isActive(), collaborator.getRole(), collaborator.getWhatsapp(),
                collaborator.getCity(), analyst == null ? null : analyst.getId(), analyst == null ? null : analyst.getName(),
                collaborator.getRole() == CollaboratorRole.ANALYST
                        ? repository.countByAssignedAnalyst_IdAndActiveTrue(collaborator.getId()) : 0,
                collaborator.getSource(), collaborator.getCreatedAt(), collaborator.getLastPortalLoginAt(),
                quotationRepository.countByConsultantId(collaborator.getId()), inspectionRepository.countByConsultantId(collaborator.getId())
        );
    }

    private String collaboratorSummary(Consultant collaborator) {
        return "nome=" + collaborator.getName()
                + "; cargo=" + collaborator.getRole()
                + "; cidade=" + (collaborator.getCity() == null ? "não informada" : collaborator.getCity())
                + "; analista=" + (collaborator.getAssignedAnalyst() == null ? "não vinculado" : collaborator.getAssignedAnalyst().getName())
                + "; whatsapp=" + (collaborator.getWhatsapp() == null ? "não cadastrado" : collaborator.getWhatsapp())
                + "; ativo=" + collaborator.isActive()
                + "; origem=" + collaborator.getSource();
    }

    private String roleLabel(CollaboratorRole role) {
        if (role == CollaboratorRole.ANALYST) return "Analista";
        if (role == CollaboratorRole.SUPERVISION_ANALYSIS) return "Supervisão de Análise";
        return "Consultor";
    }
}
