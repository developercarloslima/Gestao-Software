package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.dto.AdminUserDtos.*;
import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.entity.CollaboratorRole;
import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.entity.PortalUser;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.AssociateRepository;
import br.com.nh.cotacao.repository.ConsultantRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PortalUserService {
    /** Configurável por ambiente; nunca embutir senha compartilhada de outro cliente. */
    private String initialPassword(){
        String value=System.getenv("INITIAL_PORTAL_PASSWORD");
        if(value==null||value.length()<12)throw new IllegalStateException("Configure INITIAL_PORTAL_PASSWORD com 12+ caracteres para este tenant.");
        return value;
    }

    private static final String DEVELOPER_BOOTSTRAP = "DEVELOPER_BOOTSTRAP";

    // Mesma senha inicial dos logins genéricos deste tenant.
    // Contas pessoais sempre devem substituí-la no primeiro acesso.
    @Value("${app.tenant.module-temporary-password:gestao1234}")
    private String defaultNewUserPassword = "gestao1234";
    private final PortalUserRepository repository;
    private final CatalogChangeAuditRepository auditRepository;
    private final ConsultantRepository consultantRepository;
    private final AssociateRepository associateRepository;
    private final InspectionRequestRepository inspectionRepository;
    private final ConsultantService consultantService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public PortalUserService(
            PortalUserRepository repository,
            CatalogChangeAuditRepository auditRepository,
            ConsultantRepository consultantRepository,
            AssociateRepository associateRepository,
            InspectionRequestRepository inspectionRepository,
            ConsultantService consultantService
    ) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.consultantRepository = consultantRepository;
        this.associateRepository = associateRepository;
        this.inspectionRepository = inspectionRepository;
        this.consultantService = consultantService;
    }

    @Transactional
    public AuthenticatedPortalUser authenticate(String username, String password) {
        String normalized = PortalUser.normalizeUsername(username);
        PortalUser user = repository.findByNormalizedUsername(normalized).orElse(null);
        if (user == null) {
            user = provisionAssociateOnFirstLogin(username, password);
        }
        if (user == null || !user.isActive() || !encoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Usuário ou senha inválidos.");
        }
        return authenticatedSnapshot(user, false);
    }

    @Transactional
    public AuthenticatedPortalUser completeLogin(String username) {
        PortalUser user = repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Conta de acesso não encontrada ou inativa."));
        AuthenticatedPortalUser result = authenticatedSnapshot(user, true);
        user.registerLogin();
        repository.saveAndFlush(user);
        return result;
    }

    private AuthenticatedPortalUser authenticatedSnapshot(PortalUser user, boolean registerConsultantLogin) {
        String consultantName = null;
        if (user.getConsultantId() != null) {
            if (user.getRole() == PortalRole.CONSULTANT) {
                if (registerConsultantLogin) {
                    consultantName = consultantService.registerPortalLogin(user.getConsultantId()).name();
                } else {
                    consultantName = consultantService.findActiveConsultant(user.getConsultantId()).getName();
                }
            } else if (user.getRole() == PortalRole.ANALYST) {
                consultantName = consultantService.findActiveAnalyst(user.getConsultantId()).getName();
            } else if (user.getRole() == PortalRole.SUPERVISION_ANALYSIS) {
                consultantName = consultantService.findActiveSupervisor(user.getConsultantId()).getName();
            }
        }
        return new AuthenticatedPortalUser(user.getUsername(), user.getRole(), user.getConsultantId(), consultantName, passwordChangeRequired(user));
    }

    @Transactional(readOnly = true)
    public boolean isActiveWithRole(String username, PortalRole role) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .map(user -> user.getRole() == role)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public PortalUserSession session(String username) {
        PortalUser user = repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Conta de acesso não encontrada ou inativa."));
        return new PortalUserSession(
                user.getUsername(), user.getDisplayName(), user.getRole(), user.getConsultantId(), consultantName(user.getConsultantId()),
                passwordChangeRequired(user)
        );
    }

    @Transactional(readOnly = true)
    public Optional<UUID> linkedCollaboratorId(String username) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .map(PortalUser::getConsultantId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> linkedConsultantId(String username) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .filter(user -> user.getRole() == PortalRole.CONSULTANT)
                .map(PortalUser::getConsultantId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> linkedAssociateId(String username) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .filter(user -> user.getRole() == PortalRole.ASSOCIATE)
                .map(PortalUser::getAssociateId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> linkedAnalystId(String username) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .filter(user -> user.getRole() == PortalRole.ANALYST)
                .map(PortalUser::getConsultantId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> linkedSupervisorId(String username) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .filter(user -> user.getRole() == PortalRole.SUPERVISION_ANALYSIS)
                .map(PortalUser::getConsultantId);
    }

    @Transactional(readOnly = true)
    public boolean requiresPasswordChange(String username) {
        return repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .map(this::passwordChangeRequired)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public void assertAnalysisInspectionAccess(String username, PortalRole role, UUID inspectionId) {
        if (role == PortalRole.ADMIN) return;
        if (role != PortalRole.ANALYST) throw new IllegalArgumentException("Este usuário não possui acesso ao painel de análise.");
        Optional<UUID> linked = linkedAnalystId(username);
        var inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria não encontrada."));
        UUID assignedId = inspection.getAssignedAnalyst() == null ? null : inspection.getAssignedAnalyst().getId();
        if (linked.isEmpty()) {
            if (assignedId != null) {
                throw new IllegalArgumentException("Esta conta genérica só pode acessar vistorias ainda sem analista vinculado.");
            }
            return;
        }
        if (linked.get().equals(assignedId)) return;

        // Compatibilidade com históricos: se o responsável salvo não existe mais,
        // está inativo ou deixou de ser ANALYST, usa o vínculo atual do consultor.
        Consultant assigned = inspection.getAssignedAnalyst();
        boolean storedAssignmentUsable = assigned != null
                && assigned.isActive()
                && assigned.getRole() == CollaboratorRole.ANALYST;
        Consultant consultant = inspection.getConsultant();
        Consultant currentAnalyst = consultant == null ? null : consultant.getAssignedAnalyst();
        boolean currentAssignmentMatches = currentAnalyst != null
                && currentAnalyst.isActive()
                && currentAnalyst.getRole() == CollaboratorRole.ANALYST
                && linked.get().equals(currentAnalyst.getId());
        if (!storedAssignmentUsable && currentAssignmentMatches) return;

        throw new IllegalArgumentException("Esta vistoria está vinculada a outro analista.");
    }

    @Transactional(readOnly = true)
    public void assertSupervisionInspectionAccess(String username, PortalRole role, UUID inspectionId) {
        if (role == PortalRole.ADMIN) return;
        if (role != PortalRole.SUPERVISION_ANALYSIS) {
            throw new IllegalArgumentException("Este usuário não possui acesso à Supervisão de Análise.");
        }
        var inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria não encontrada."));
        boolean visible = inspection.getAnalysisStage() == br.com.nh.cotacao.entity.InspectionAnalysisStage.ANALYST_QUEUE
                || inspection.getAnalysisStage() == br.com.nh.cotacao.entity.InspectionAnalysisStage.ANALYST_PENDING
                || inspection.getAnalysisStage() == br.com.nh.cotacao.entity.InspectionAnalysisStage.SUPERVISION_QUEUE
                || inspection.getStatus() == br.com.nh.cotacao.entity.InspectionRequestStatus.APPROVED
                || inspection.getStatus() == br.com.nh.cotacao.entity.InspectionRequestStatus.REJECTED;
        if (!visible) throw new IllegalArgumentException("Esta vistoria não faz parte do fluxo de análise/supervisão.");
    }

    @Transactional(readOnly = true)
    public void assertConsultantAccess(String username, PortalRole role, UUID requestedConsultantId) {
        if (role == PortalRole.ADMIN) return;
        if (role != PortalRole.CONSULTANT) {
            throw new IllegalArgumentException("Este usuário não possui acesso ao painel de consultor.");
        }
        Optional<UUID> linked = linkedConsultantId(username);
        if (linked.isPresent() && !linked.get().equals(requestedConsultantId)) {
            throw new IllegalArgumentException("Este login está vinculado a outro consultor.");
        }
    }

    @Transactional(readOnly = true)
    public void assertInspectionAccess(String username, PortalRole role, UUID inspectionId) {
        if (role == PortalRole.ADMIN) return;
        Optional<UUID> linked = linkedConsultantId(username);
        if (linked.isEmpty()) return; // usuário consultor padrão mantém o comportamento legado
        var inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria não encontrada."));
        UUID ownerId = inspection.getConsultant() == null ? null : inspection.getConsultant().getId();
        if (!linked.get().equals(ownerId)) {
            throw new IllegalArgumentException("Esta vistoria pertence a outro consultor.");
        }
    }

    @Transactional(readOnly = true)
    public List<PortalUserResponse> list() {
        return repository.findAllByOrderByRoleAscUsernameAsc().stream()
                .filter(user -> !isProtectedDeveloper(user))
                .filter(user -> !"ASSOCIATE_AUTO_BOOTSTRAP".equalsIgnoreCase(user.getCreatedBy()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PortalUserResponse create(CreatePortalUserRequest request, String adminUsername) {
        // O ADM não escolhe a senha inicial; o tenant define o padrão.
        return createWithPassword(request, adminUsername, defaultNewUserPassword);
    }

    private PortalUserResponse createWithPassword(
            CreatePortalUserRequest request, String adminUsername, String temporaryPassword
    ) {
        String normalized = PortalUser.normalizeUsername(request.username());
        repository.findByNormalizedUsername(normalized).ifPresent(existing -> {
            throw new IllegalArgumentException("Já existe uma conta com esse usuário.");
        });
        validatePassword(temporaryPassword);

        UUID consultantId = resolveConsultantForNewUser(
                request.role(), request.consultantId(), request.newConsultantName(), adminUsername
        );
        String displayName = normalizeDisplayName(request.displayName(), request.username(), consultantId);

        PortalUser user = PortalUser.create(
                request.username(), displayName, encoder.encode(temporaryPassword), request.role(), adminUsername
        );
        user.linkConsultant(consultantId);
        // Contas pessoais criadas pelo ADM sempre trocam a senha no primeiro acesso.
        user.requirePasswordChange();
        PortalUser saved = repository.save(user);
        auditRepository.save(CatalogChangeAudit.createText(
                "PORTAL_USER", null, saved.getId().toString(),
                "Conta de acesso criada — " + saved.getUsername(), null, summary(saved), adminUsername
        ));
        return toResponse(saved);
    }

    /** Importação apenas no banco deste tenant; nunca sobrescreve contas existentes. */
    @Transactional
    public PortalUserResponse createImported(String login,String name,PortalRole role,
            String temporaryPassword,String adminUsername) {
        UUID collaboratorId=null;
        String newCollaboratorName=null;
        if(role==PortalRole.CONSULTANT||role==PortalRole.ANALYST||role==PortalRole.SUPERVISION_ANALYSIS){
            var existing=consultantRepository.findByNormalizedName(Consultant.normalize(name));
            if(existing.isPresent()){
                if(!existing.get().isActive()||!existing.get().getRole().name().equals(role.name()))
                    throw new IllegalArgumentException("Colaborador existente possui outro cargo ou está inativo: "+name);
                collaboratorId=existing.get().getId();
            }else newCollaboratorName=name;
        }
        // A importação preserva a senha temporária do lote e exige troca no primeiro login.
        PortalUserResponse created=createWithPassword(new CreatePortalUserRequest(login,name,null,
                role,collaboratorId,newCollaboratorName),adminUsername,temporaryPassword);
        PortalUser account=find(created.id());
        account.requirePasswordChange();
        repository.saveAndFlush(account);
        return toResponse(account);
    }

    @Transactional
    public PortalUserResponse update(UUID id, UpdatePortalUserRequest request, String adminUsername) {
        PortalUser user = find(id);
        assertNotProtectedDeveloper(user);
        String old = summary(user);
        String requestedUsername = request.username();
        if (requestedUsername != null && !requestedUsername.isBlank()) {
            String normalized = PortalUser.normalizeUsername(requestedUsername);
            repository.findByNormalizedUsername(normalized).ifPresent(existing -> {
                if (!existing.getId().equals(id)) throw new IllegalArgumentException("Já existe uma conta com esse usuário.");
            });
        }

        PortalRole nextRole = request.role() == null ? user.getRole() : request.role();
        boolean nextActive = request.active() == null ? user.isActive() : request.active();
        protectLastAdmin(user, nextRole, nextActive);

        UUID nextConsultantId = resolveConsultantForUpdate(user, nextRole, request.consultantId(), request.newConsultantName(), adminUsername);
        String nextUsername = requestedUsername == null || requestedUsername.isBlank() ? user.getUsername() : requestedUsername;
        if (isGestaoModuleAccount(user) && !nextUsername.equals(user.getUsername())) {
            throw new IllegalArgumentException("O login genérico do módulo é fixo. Altere apenas a senha pelo painel.");
        }
        String displayName = request.displayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = normalizeDisplayName(null, nextUsername, nextConsultantId);
        }

        user.updateProfile(requestedUsername, displayName, request.role());
        user.linkConsultant(nextConsultantId);
        if (request.active() != null) user.setActive(request.active());
        PortalUser saved = repository.save(user);
        auditRepository.save(CatalogChangeAudit.createText(
                "PORTAL_USER", null, saved.getId().toString(),
                "Conta de acesso alterada — " + saved.getUsername(), old, summary(saved), adminUsername
        ));
        return toResponse(saved);
    }

    @Transactional
    public PortalUserResponse changePassword(UUID id, ChangePortalUserPasswordRequest request, String adminUsername) {
        PortalUser user = find(id);
        assertNotProtectedDeveloper(user);
        validatePassword(request.password());
        user.changePassword(encoder.encode(request.password()));
        PortalUser saved = repository.save(user);
        auditRepository.save(CatalogChangeAudit.createText(
                "PORTAL_USER", null, saved.getId().toString(),
                "Senha da conta alterada — " + saved.getUsername(), "senha anterior protegida", "nova senha protegida por BCrypt", adminUsername
        ));
        return toResponse(saved);
    }

    /**
     * Exclui exclusivamente a conta de acesso. O cadastro do colaborador,
     * as cotações, as vistorias e os registros de atividade permanecem.
     * As passkeys e os tokens de redefinição pertencentes à conta são
     * removidos pelas respectivas FKs ON DELETE CASCADE.
     */
    @Transactional
    public void delete(UUID id, String adminUsername) {
        PortalUser user = find(id);
        assertNotProtectedDeveloper(user);
        if (isSystemManagedAccount(user)) {
            throw new IllegalArgumentException("Usuários padrão dos módulos e contas de implantação não podem ser excluídos.");
        }
        if (user.getNormalizedUsername().equals(PortalUser.normalizeUsername(adminUsername))) {
            throw new IllegalArgumentException("Você não pode excluir seu próprio usuário enquanto está conectado.");
        }
        protectLastAdmin(user, user.getRole(), false);
        String login = user.getUsername();
        String previous = summary(user);
        repository.delete(user);
        repository.flush();
        auditRepository.save(CatalogChangeAudit.createText(
                "PORTAL_USER", null, id.toString(),
                "Conta de acesso excluída — " + login, previous,
                "Conta removida definitivamente; colaborador e históricos preservados", adminUsername
        ));
    }

    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        PortalUser user = repository.findByNormalizedUsername(PortalUser.normalizeUsername(username))
                .filter(PortalUser::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Conta de acesso não encontrada ou inativa."));
        if (!encoder.matches(currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("A senha atual está incorreta.");
        }
        validatePassword(newPassword);
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("A nova senha deve ser diferente da senha atual.");
        }
        user.changePassword(encoder.encode(newPassword));
        repository.saveAndFlush(user);
    }

    @Transactional
    public void bootstrapTowDriver(String username, String password) {
        String normalized = PortalUser.normalizeUsername(username);
        if (normalized.isBlank()) throw new IllegalStateException("Usuário inicial de guincho/reboque não foi configurado.");
        Optional<PortalUser> existing = repository.findByNormalizedUsername(normalized);
        if (existing.isPresent()) {
            PortalUser user = existing.get();
            user.clearPasswordChangeRequirement();
            repository.save(user);
            return;
        }
        String configuredPassword = password == null || password.isBlank() ? initialPassword() : password;
        PortalUser user = PortalUser.create(
                username, "Equipe de Guincho / Reboque", encoder.encode(configuredPassword), PortalRole.TOW_DRIVER, "TOW_BOOTSTRAP"
        );
        repository.save(user);
    }

    @Transactional
    public void bootstrapWorkshopManager(String username, String password) {
        String normalized = PortalUser.normalizeUsername(username);
        if (normalized.isBlank()) throw new IllegalStateException("Usuário inicial da oficina não foi configurado.");
        Optional<PortalUser> existing = repository.findByNormalizedUsername(normalized);
        if (existing.isPresent()) {
            PortalUser user = existing.get();
            user.clearPasswordChangeRequirement();
            repository.save(user);
            return;
        }
        String configuredPassword = password == null || password.isBlank() ? initialPassword() : password;
        PortalUser user = PortalUser.create(
                username, "Gerência da Oficina", encoder.encode(configuredPassword), PortalRole.WORKSHOP_MANAGER, "WORKSHOP_BOOTSTRAP"
        );
        repository.save(user);
    }

    @Transactional
    public void bootstrapEventOperator(String username, String password) {
        bootstrapOperationalUser(username, password, "Equipe de Eventos", PortalRole.EVENT_OPERATOR, "EVENT_BOOTSTRAP");
    }

    @Transactional
    public void bootstrapBuyer(String username, String password) {
        bootstrapOperationalUser(username, password, "Financeiro", PortalRole.BUYER, "BUYER_BOOTSTRAP");
    }

    private void bootstrapOperationalUser(String username, String password, String displayName, PortalRole role, String createdBy) {
        String normalized = PortalUser.normalizeUsername(username);
        if (normalized.isBlank()) throw new IllegalStateException("Usuário operacional não configurado: " + role);
        Optional<PortalUser> existing = repository.findByNormalizedUsername(normalized);
        if (existing.isPresent()) {
            PortalUser user = existing.get();
            user.clearPasswordChangeRequirement();
            repository.save(user);
            return;
        }
        String configuredPassword = password == null || password.isBlank() ? initialPassword() : password;
        PortalUser user = PortalUser.create(username, displayName, encoder.encode(configuredPassword), role, createdBy);
        user.clearPasswordChangeRequirement();
        repository.save(user);
    }


    private PortalUser provisionAssociateOnFirstLogin(String username, String password) {
        if ("true".equalsIgnoreCase(System.getenv("GS_TENANT_MODE"))) return null; // contas por convite, sem senha compartilhada
        String document = username == null ? "" : username.replaceAll("\\D", "");
        if ((document.length() != 11 && document.length() != 14) || !initialPassword().equals(password)) return null;
        var associate = associateRepository.findByDocument(document).filter(br.com.nh.cotacao.entity.Associate::isActive).orElse(null);
        if (associate == null) return null;
        var existing = repository.findByAssociateId(associate.getId()).orElse(null);
        if (existing != null) return existing;
        PortalUser user = PortalUser.create(
                document, associate.getName(), encoder.encode(initialPassword()), PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP"
        );
        user.linkAssociate(associate.getId());
        user.requirePasswordChange();
        return repository.save(user);
    }

    @Transactional
    public String ensureAssociateAccount(UUID associateId) {
        var associate = associateRepository.findById(associateId)
                .filter(br.com.nh.cotacao.entity.Associate::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Associado não encontrado."));
        if (associate.getDocument() == null) throw new IllegalArgumentException("Cadastre CPF/CNPJ para liberar a Área do Associado.");
        return repository.findByAssociateId(associateId).map(PortalUser::getUsername).orElseGet(() -> {
            PortalUser user = PortalUser.create(
                    associate.getDocument(), associate.getName(), encoder.encode(initialPassword()), PortalRole.ASSOCIATE, "ASSOCIATE_AUTO_BOOTSTRAP"
            );
            user.linkAssociate(associateId);
            user.requirePasswordChange();
            return repository.save(user).getUsername();
        });
    }

    /**
     * Não recriar contas individuais automaticamente na inicialização.
     * Somente o ADM pode liberar um acesso pessoal para um colaborador;
     * caso contrário, ele é identificado na Área do Colaborador genérica.
     */
    @Transactional
    public void bootstrapConsultantAccounts() {
        // Intencionalmente vazio. Contas antigas existentes não são alteradas.
    }

    @Transactional
    public String ensureConsultantAccount(UUID consultantId) {
        Consultant consultant = consultantRepository.findById(consultantId)
                .orElseThrow(() -> new IllegalArgumentException("Consultor não encontrado."));
        if (consultant.getRole() != CollaboratorRole.CONSULTANT) return null;

        String base = consultantUsernameBase(consultant.getName());
        Optional<PortalUser> linked = repository.findByConsultantId(consultantId);
        if (linked.isPresent()) {
            PortalUser current = linked.get();
            if (current.getRole() == PortalRole.CONSULTANT
                    && "CONSULTANT_AUTO_BOOTSTRAP".equals(current.getCreatedBy())) {
                String candidate = availableConsultantUsername(base, current.getId());
                if (!PortalUser.normalizeUsername(candidate).equals(current.getNormalizedUsername())) {
                    current.updateProfile(candidate, consultant.getName(), PortalRole.CONSULTANT);
                    repository.save(current);
                }
            }
            return current.getUsername();
        }

        String candidate = availableConsultantUsername(base, null);
        PortalUser user = PortalUser.create(
                candidate, consultant.getName(), encoder.encode(initialPassword()), PortalRole.CONSULTANT, "CONSULTANT_AUTO_BOOTSTRAP"
        );
        user.linkConsultant(consultant.getId());
        user.requirePasswordChange();
        repository.save(user);
        return candidate;
    }

    /**
     * Quando o ADM cria um acesso para um nome já presente na Área do Colaborador,
     * reaproveita a conta antiga (se houver) e permite sua reativação consciente.
     * A migração retroativa nunca apaga o vínculo nem o histórico da pessoa.
     */
    @Transactional
    public String ensureConsultantAccountByAdmin(UUID consultantId, String adminUsername) {
        String login = ensureConsultantAccount(consultantId);
        repository.findByConsultantId(consultantId).ifPresent(account -> {
            if (!account.isActive()) {
                account.setActive(true);
                account.requirePasswordChange();
                repository.save(account);
                auditRepository.save(CatalogChangeAudit.createText(
                        "PORTAL_USER", null, account.getId().toString(),
                        "Conta individual reativada pelo ADM — " + account.getUsername(),
                        "inativa", "ativa; troca de senha necessária", adminUsername
                ));
            }
        });
        return login;
    }

    private String consultantUsernameBase(String fullName) {
        String normalized = Consultant.normalize(fullName);
        if (normalized.isBlank()) return "consultor";

        String[] parts = normalized.split("\\s+");
        String first = parts[0];
        String firstSurname = null;
        for (int i = 1; i < parts.length; i++) {
            if (!isNameConnector(parts[i])) {
                firstSurname = parts[i];
                break;
            }
        }
        return firstSurname == null ? first : first + "." + firstSurname;
    }

    private boolean isNameConnector(String part) {
        return "da".equals(part) || "das".equals(part) || "de".equals(part)
                || "do".equals(part) || "dos".equals(part) || "e".equals(part);
    }

    private String availableConsultantUsername(String base, UUID currentUserId) {
        String candidate = base;
        int suffix = 2;
        while (true) {
            Optional<PortalUser> existing = repository.findByNormalizedUsername(PortalUser.normalizeUsername(candidate));
            if (existing.isEmpty() || (currentUserId != null && currentUserId.equals(existing.get().getId()))) {
                return candidate;
            }
            candidate = base + suffix++;
        }
    }

    @Transactional
    public void bootstrapDeveloperAdmin(String username, String password) {
        String normalized = PortalUser.normalizeUsername(username);
        if (normalized.isBlank()) return;
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("DEVELOPER_PASSWORD precisa ser definida quando DEVELOPER_USERNAME estiver configurado.");
        }
        validatePassword(password);

        Optional<PortalUser> existing = repository.findByNormalizedUsername(normalized);
        if (existing.isPresent()) {
            PortalUser user = existing.get();
            if (!isProtectedDeveloper(user)) {
                throw new IllegalStateException("DEVELOPER_USERNAME já pertence a uma conta comum. Escolha outro usuário.");
            }
            // Nunca sobrescreve a senha posteriormente: ela só muda pelo próprio login.
            if (user.getRole() != PortalRole.ADMIN || !user.isActive()) {
                user.updateProfile(null, "Carlos Lima — Desenvolvedor", PortalRole.ADMIN);
                user.setActive(true);
                repository.save(user);
            }
            return;
        }

        PortalUser developer = PortalUser.create(
                username, "Carlos Lima — Desenvolvedor", encoder.encode(password), PortalRole.ADMIN, DEVELOPER_BOOTSTRAP
        );
        developer.clearPasswordChangeRequirement();
        repository.save(developer);
    }

    @Transactional
    public void bootstrapAnalysisTeam() {
        seedTeamUser("Gleyce", "Teotônio Vilela", CollaboratorRole.ANALYST, "analiseGleyce", PortalRole.ANALYST);
        seedTeamUser("Larissa", "Maceió", CollaboratorRole.ANALYST, "analiseLarissa", PortalRole.ANALYST);
        seedTeamUser("Gabriele", "Coruripe", CollaboratorRole.ANALYST, "analiseGabriele", PortalRole.ANALYST);
        seedTeamUser("Flavia", null, CollaboratorRole.ANALYST, "analiseFlavia", PortalRole.ANALYST);
        seedTeamUser("Luana", null, CollaboratorRole.ANALYST, "analiseLuana", PortalRole.ANALYST);
        seedTeamUser("Livia", null, CollaboratorRole.ANALYST, "analiseLivia", PortalRole.ANALYST);
        seedTeamUser("Gabrielly", null, CollaboratorRole.ANALYST, "analiseGabrielly", PortalRole.ANALYST);
        seedTeamUser("Eurides", null, CollaboratorRole.SUPERVISION_ANALYSIS, "SupervisaoEurides", PortalRole.SUPERVISION_ANALYSIS);
    }

    private void seedTeamUser(String name, String city, CollaboratorRole collaboratorRole, String username, PortalRole portalRole) {
        Consultant collaborator = consultantRepository.findByNormalizedName(Consultant.normalize(name)).orElse(null);
        if (collaborator != null && collaborator.getRole() != collaboratorRole) {
            // Não interrompe o deploy caso já exista, por exemplo, um consultor chamado "Flavia".
            // Nesse caso cria um cadastro operacional distinto para a equipe de análise.
            String operationalName = name + (collaboratorRole == CollaboratorRole.SUPERVISION_ANALYSIS
                    ? " - Supervisão" : " - Análise");
            collaborator = consultantRepository.findByNormalizedName(Consultant.normalize(operationalName))
                    .orElseGet(() -> Consultant.create(operationalName, "ANALYSIS_TEAM_BOOTSTRAP", collaboratorRole));
        }
        if (collaborator == null) {
            collaborator = Consultant.create(name, "ANALYSIS_TEAM_BOOTSTRAP", collaboratorRole);
        }
        if (!collaborator.isActive()) collaborator.setActive(true);
        if (city != null) collaborator.setCity(city);
        collaborator = consultantRepository.save(collaborator);

        String normalized = PortalUser.normalizeUsername(username);
        if (repository.findByNormalizedUsername(normalized).isPresent()) return;
        if (repository.findByConsultantId(collaborator.getId()).isPresent()) return;

        PortalUser user = PortalUser.create(
                username, collaborator.getName(), encoder.encode(initialPassword()), portalRole, "ANALYSIS_TEAM_BOOTSTRAP"
        );
        user.linkConsultant(collaborator.getId());
        user.requirePasswordChange();
        repository.save(user);
    }

    /** Idempotente: instala apenas o administrador da nova empresa e obriga troca de senha. */
    @Transactional
    public void bootstrapTenantAdmin(String username, String password, String tenantName, boolean approvedDemoPassword) {
        String normalized = PortalUser.normalizeUsername(username);
        if (normalized.isBlank() || password == null || (password.length() < 8 && !approvedDemoPassword)
                || tenantName == null || tenantName.isBlank()) {
            throw new IllegalStateException("Dados do administrador inicial são obrigatórios; senhas de demonstração exigem aprovação explícita.");
        }
        Optional<PortalUser> current = repository.findByNormalizedUsername(normalized);
        if (current.isPresent()) {
            if (current.get().getRole() != PortalRole.ADMIN) {
                throw new IllegalStateException("Login do administrador pertence a outro perfil; implantação bloqueada.");
            }
            return; // nunca sobrescrever credenciais ou o aceite do usuário existente
        }
        PortalUser admin = PortalUser.create(username, "Administrador · " + tenantName,
                encoder.encode(password), PortalRole.ADMIN, "TENANT_BOOTSTRAP");
        admin.requirePasswordChange();
        repository.save(admin);
    }

    /**
     * Contas de módulo são compartilhadas: não representam um colaborador
     * específico. A seleção da pessoa ocorre no portal e as atividades usam
     * o ID do consultor escolhido, também visível no login individual dele.
     * Backfills preservam a senha escolhida pelo ADM e nunca alteram contas pessoais.
     */
    @Transactional
    public void bootstrapTenantModuleUser(String username, String displayName,
            PortalRole role, String temporaryPassword) {
        if (temporaryPassword == null || temporaryPassword.length() < 8)
            throw new IllegalArgumentException("Senha temporária precisa de 8+ caracteres.");
        String normalized = PortalUser.normalizeUsername(username);
        Optional<PortalUser> current = repository.findByNormalizedUsername(normalized);

        // Migra o login genérico antigo sem perder senha, passkey ou histórico.
        if (current.isEmpty() && role == PortalRole.CONSULTANT && "colaborador".equals(normalized)) {
            Optional<PortalUser> old = repository.findByNormalizedUsername("consultor");
            if (old.isPresent() && isGestaoModuleAccount(old.get())
                    && old.get().getRole() == PortalRole.CONSULTANT) {
                PortalUser generic = old.get();
                generic.updateProfile("colaborador", displayName, role);
                generic.linkConsultant(null);
                generic.clearPasswordChangeRequirement();
                repository.save(generic);
                return;
            }
        }

        if (current.isPresent()) {
            PortalUser existing = current.get();
            if (isGestaoModuleAccount(existing) && existing.getRole() == role) {
                // Versões anteriores vinculavam contas genéricas a pessoas fictícias.
                // Nunca associar o login compartilhado ao histórico de uma pessoa.
                boolean changed = false;
                if (existing.getConsultantId() != null) {
                    existing.linkConsultant(null);
                    changed = true;
                }
                if (existing.isMustChangePassword()) {
                    existing.clearPasswordChangeRequirement();
                    changed = true;
                }
                if (!displayName.equals(existing.getDisplayName())) {
                    existing.updateProfile(null, displayName, null);
                    changed = true;
                }
                if (changed) repository.save(existing);
            }
            return;
        }

        PortalUser user = PortalUser.create(username, displayName,
                encoder.encode(temporaryPassword), role, "GESTAO_MODULE_BOOTSTRAP");
        repository.save(user);
    }

    private boolean isGestaoModuleAccount(PortalUser user) {
        return user != null && "GESTAO_MODULE_BOOTSTRAP".equals(user.getCreatedBy());
    }

    @Transactional
    public void bootstrapDefaults(
            String adminUsername, String adminPassword,
            String analystUsername, String analystPassword,
            String consultantUsername, String consultantPassword
    ) {
        // Os três usuários padrão continuam existindo. O consultor padrão permanece
        // sem vínculo específico para preservar a seleção manual legada do portal.
        if (repository.count() > 0) return;
        createBootstrapUser(adminUsername, "" + TenantIdentity.adminDisplayName() + "", adminPassword, PortalRole.ADMIN);
        createBootstrapUser(analystUsername, "Equipe de análise", analystPassword, PortalRole.ANALYST);
        createBootstrapUser(consultantUsername, "Portal de consultores", consultantPassword, PortalRole.CONSULTANT);
    }

    private void createBootstrapUser(String username, String displayName, String configuredPassword, PortalRole role) {
        String normalized = PortalUser.normalizeUsername(username);
        if (normalized.isBlank()) throw new IllegalStateException("Usuário inicial de " + role + " não foi configurado.");
        if (repository.findByNormalizedUsername(normalized).isPresent()) {
            throw new IllegalStateException("Os usuários iniciais dos portais precisam ser diferentes entre si.");
        }
        String hash = configuredPassword != null && configuredPassword.startsWith("$2")
                ? configuredPassword
                : encoder.encode(configuredPassword == null ? "" : configuredPassword);
        repository.save(PortalUser.create(username, displayName, hash, role, "BOOTSTRAP"));
    }

    private UUID resolveConsultantForNewUser(
            PortalRole role,
            UUID collaboratorId,
            String newCollaboratorName,
            String adminUsername
    ) {
        if (role != PortalRole.CONSULTANT && role != PortalRole.ANALYST && role != PortalRole.SUPERVISION_ANALYSIS) return null;
        UUID resolved = resolveCollaborator(role, collaboratorId, newCollaboratorName, adminUsername);
        if (resolved == null) {
            throw new IllegalArgumentException("Selecione um colaborador existente ou informe o nome do novo colaborador.");
        }
        ensureCollaboratorAvailable(resolved, null);
        return resolved;
    }

    private UUID resolveConsultantForUpdate(
            PortalUser user,
            PortalRole nextRole,
            UUID collaboratorId,
            String newCollaboratorName,
            String adminUsername
    ) {
        if (isGestaoModuleAccount(user)) {
            if (nextRole != user.getRole()) {
                throw new IllegalArgumentException("O cargo da conta genérica é definido pelo módulo contratado.");
            }
            if (collaboratorId != null || (newCollaboratorName != null && !newCollaboratorName.isBlank())) {
                throw new IllegalArgumentException("A conta genérica não pode ser vinculada a uma pessoa específica.");
            }
            return null;
        }
        if (nextRole != PortalRole.CONSULTANT && nextRole != PortalRole.ANALYST && nextRole != PortalRole.SUPERVISION_ANALYSIS) return null;

        UUID resolved = resolveCollaborator(nextRole, collaboratorId, newCollaboratorName, adminUsername);
        if (resolved == null) {
            // Os usuários padrão criados no bootstrap podem continuar sem vínculo específico.
            if (user.getRole() == nextRole
                    && user.getConsultantId() == null
                    && ("BOOTSTRAP".equalsIgnoreCase(user.getCreatedBy())
                        || isGestaoModuleAccount(user))) {
                return null;
            }
            if (user.getRole() == nextRole) resolved = user.getConsultantId();
        }
        if (resolved == null) {
            throw new IllegalArgumentException("Selecione um colaborador existente ou informe o nome do novo colaborador.");
        }
        ensureCollaboratorAvailable(resolved, user.getId());
        return resolved;
    }

    private UUID resolveCollaborator(
            PortalRole role,
            UUID collaboratorId,
            String newCollaboratorName,
            String adminUsername
    ) {
        CollaboratorRole collaboratorRole = switch (role) {
            case ANALYST -> CollaboratorRole.ANALYST;
            case SUPERVISION_ANALYSIS -> CollaboratorRole.SUPERVISION_ANALYSIS;
            default -> CollaboratorRole.CONSULTANT;
        };
        if (newCollaboratorName != null && !newCollaboratorName.isBlank()) {
            return consultantService.create(
                    newCollaboratorName, collaboratorRole, "CREATED_WITH_PORTAL_USER", adminUsername
            ).id();
        }
        if (collaboratorId != null) {
            return consultantService.findActiveWithRole(collaboratorId, collaboratorRole).getId();
        }
        return null;
    }

    private void ensureCollaboratorAvailable(UUID collaboratorId, UUID currentUserId) {
        repository.findByConsultantId(collaboratorId).ifPresent(existing -> {
            if (currentUserId == null || !existing.getId().equals(currentUserId)) {
                throw new IllegalArgumentException("Este colaborador já possui um usuário específico vinculado.");
            }
        });
    }

    private String normalizeDisplayName(String displayName, String username, UUID consultantId) {
        if (consultantId != null) {
            return consultantName(consultantId);
        }
        if (displayName != null && !displayName.isBlank()) return displayName.trim();
        return username == null ? null : username.trim();
    }

    private String consultantName(UUID consultantId) {
        if (consultantId == null) return null;
        return consultantRepository.findById(consultantId).map(Consultant::getName).orElse(null);
    }

    private boolean passwordChangeRequired(PortalUser user) {
        if (user == null || !user.isMustChangePassword()) return false;
        String source = user.getCreatedBy();
        // Contas genéricas de serviço permanecem com senha fixa até que um administrador
        // decida alterá-la. A troca obrigatória fica reservada às contas pessoais.
        return !("BOOTSTRAP".equals(source)
                || "TOW_BOOTSTRAP".equals(source)
                || "WORKSHOP_BOOTSTRAP".equals(source)
                || "EVENT_BOOTSTRAP".equals(source)
                || "BUYER_BOOTSTRAP".equals(source)
                || "GESTAO_MODULE_BOOTSTRAP".equals(source)
                || DEVELOPER_BOOTSTRAP.equals(source));
    }

    private boolean faceValidationRequired(PortalUser user) {
        // v83.19: reconhecimento facial não é fator de login para nenhum perfil.
        return false;
    }

    private boolean isSystemManagedAccount(PortalUser user) {
        if (user == null || user.getCreatedBy() == null) return false;
        String source = user.getCreatedBy().toUpperCase(java.util.Locale.ROOT);
        return "BOOTSTRAP".equals(source)
                || (source.endsWith("_BOOTSTRAP") && !"CONSULTANT_AUTO_BOOTSTRAP".equals(source));
    }

    private boolean isProtectedDeveloper(PortalUser user) {
        return user != null && DEVELOPER_BOOTSTRAP.equalsIgnoreCase(user.getCreatedBy());
    }

    private void assertNotProtectedDeveloper(PortalUser user) {
        if (isProtectedDeveloper(user)) {
            throw new IllegalArgumentException("Esta é uma conta técnica protegida do desenvolvedor e não pode ser alterada pelo painel administrativo.");
        }
    }

    private void protectLastAdmin(PortalUser user, PortalRole nextRole, boolean nextActive) {
        if (user.getRole() != PortalRole.ADMIN || !user.isActive()) return;
        boolean losesAdmin = nextRole != PortalRole.ADMIN || !nextActive;
        if (losesAdmin && repository.countByRoleAndActiveTrue(PortalRole.ADMIN) <= 1) {
            throw new IllegalArgumentException("Não é possível remover ou desativar o último administrador ativo.");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("A senha deve ter pelo menos 8 caracteres.");
        }
        if (password.length() > 120) throw new IllegalArgumentException("A senha é muito longa.");
    }

    private PortalUser find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Conta de acesso não encontrada."));
    }

    private PortalUserResponse toResponse(PortalUser user) {
        return new PortalUserResponse(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.isActive(),
                user.getCreatedAt(), user.getUpdatedAt(), user.getPasswordChangedAt(), user.getLastLoginAt(), user.getCreatedBy(),
                user.getConsultantId(), consultantName(user.getConsultantId()), passwordChangeRequired(user),
                faceValidationRequired(user), user.getFaceEnrolledAt() != null, user.getFaceEnrolledAt(),
                user.getRole() == PortalRole.ADMIN, user.getPasskeyEnrolledAt() != null, user.getPasskeyEnrolledAt()
        );
    }

    private String summary(PortalUser user) {
        return "usuario=" + user.getUsername()
                + "; perfil=" + user.getRole()
                + "; ativo=" + user.isActive()
                + "; colaborador=" + (consultantName(user.getConsultantId()) == null ? "sem vínculo específico" : consultantName(user.getConsultantId()));
    }

    public record AuthenticatedPortalUser(
            String username,
            PortalRole role,
            UUID consultantId,
            String consultantName,
            boolean passwordChangeRequired
    ) {}

    public record PortalUserSession(
            String username,
            String displayName,
            PortalRole role,
            UUID consultantId,
            String consultantName,
            boolean passwordChangeRequired
    ) {}
}
