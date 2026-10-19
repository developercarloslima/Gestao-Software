package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.TenantIdentity;
import br.com.nh.cotacao.dto.AdminDtos.*;
import br.com.nh.cotacao.entity.*;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import br.com.nh.cotacao.repository.InspectionRequestRepository;
import br.com.nh.cotacao.repository.QuotationRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminActivityService {
    private static final String DEFAULT_PUBLIC_WEB_URL = "https://aforma-demo.vercel.app";
    private static final String ADMIN_RESPONSIBLE_NAME = "" + TenantIdentity.adminDisplayName() + "";
    private final QuotationRepository quotationRepository;
    private final InspectionRequestRepository inspectionRepository;
    private final CatalogChangeAuditRepository auditRepository;
    private final CommunicationSettingsService settingsService;
    private final InspectionAssetStorageService storageService;
    private final ConsultantService consultantService;
    private final PortalUserService portalUserService;
    private final RetratoService retratoService;
    private final RetratoPdfService retratoPdfService;
    private final CommercialPricingService commercialPricingService;
    private final DiscountRuleService discountRuleService;
    private final ThirdPartyRuleSettingsService thirdPartyRuleSettingsService;
    private final InspectionFipeSelectionService fipeSelectionService;
    private final InspectionAcceptanceWorkflowService acceptanceWorkflow;
    private final String publicApiUrl;
    private final String publicWebUrl;

    public AdminActivityService(
            QuotationRepository quotationRepository,
            InspectionRequestRepository inspectionRepository,
            CatalogChangeAuditRepository auditRepository,
            CommunicationSettingsService settingsService,
            InspectionAssetStorageService storageService,
            ConsultantService consultantService,
            PortalUserService portalUserService,
            RetratoService retratoService,
            RetratoPdfService retratoPdfService,
            CommercialPricingService commercialPricingService,
            DiscountRuleService discountRuleService,
            ThirdPartyRuleSettingsService thirdPartyRuleSettingsService,
            InspectionFipeSelectionService fipeSelectionService,
            InspectionAcceptanceWorkflowService acceptanceWorkflow,
            @Value("${app.public-api-url:http://localhost:8080}") String publicApiUrl,
            @Value("${app.public-web-url:https://aforma-demo.vercel.app}") String publicWebUrl
    ) {
        this.quotationRepository = quotationRepository;
        this.inspectionRepository = inspectionRepository;
        this.auditRepository = auditRepository;
        this.settingsService = settingsService;
        this.storageService = storageService;
        this.consultantService = consultantService;
        this.portalUserService = portalUserService;
        this.retratoService = retratoService;
        this.retratoPdfService = retratoPdfService;
        this.commercialPricingService = commercialPricingService;
        this.discountRuleService = discountRuleService;
        this.thirdPartyRuleSettingsService = thirdPartyRuleSettingsService;
        this.fipeSelectionService = fipeSelectionService;
        this.acceptanceWorkflow = acceptanceWorkflow;
        this.publicApiUrl = stripTrailingSlash(publicApiUrl);
        this.publicWebUrl = normalizePublicWebUrl(publicWebUrl);
    }

    @Transactional(readOnly = true)
    public List<AdminQuoteResponse> quotes() {
        return quotationRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toQuote).toList();
    }

    @Transactional
    public AdminQuoteResponse updateQuoteConsultant(UUID id, UpdateQuoteConsultantRequest request, String username) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cotação não encontrada."));
        if (quotation.getOrigin() != QuoteOrigin.SELF_SERVICE) {
            throw new IllegalArgumentException("A troca de responsável por esta tela é permitida apenas para cotações feitas pelo site.");
        }

        Consultant consultant = consultantService.findActive(request.consultantId());
        String oldConsultant = quotation.getConsultantName();
        quotation.assignConsultant(consultant);

        inspectionRepository.findByQuotation_Id(quotation.getId()).ifPresent(inspection ->
                inspection.assignConsultant(consultant)
        );

        quotationRepository.flush();
        inspectionRepository.flush();

        // Se o cliente já aceitou enquanto a distribuição automática estava desligada,
        // a atribuição manual conclui a preparação da vistoria sem exigir nova ação dele.
        if (quotation.getStatus() == QuoteStatus.ACCEPTED
                && inspectionRepository.findByQuotation_Id(quotation.getId()).isEmpty()) {
            retratoService.ensureForSelfServiceQuote(quotation);
        }

        auditRepository.save(CatalogChangeAudit.createText(
                "QUOTE_CONSULTANT", null, id.toString(),
                "Responsável da cotação " + quotation.getQuoteNumber() + " alterado",
                "consultor=" + oldConsultant,
                "consultor=" + consultant.getName(),
                username
        ));
        return toQuote(quotation);
    }

    @Transactional
    public AdminQuoteResponse updateQuoteDetails(
            UUID id, UpdateAdminQuoteDetailsRequest request, String username, PortalRole actorRole
    ) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cotação não encontrada."));
        InspectionRequest inspection = inspectionRepository.findByQuotation_Id(id).orElse(null);

        if (actorRole == PortalRole.ANALYST) {
            if (inspection == null) throw new IllegalArgumentException("O analista só pode editar cotações com vistoria vinculada.");
            portalUserService.assertAnalysisInspectionAccess(username, actorRole, inspection.getId());
        } else if (actorRole == PortalRole.SUPERVISION_ANALYSIS) {
            if (inspection == null) throw new IllegalArgumentException("A Supervisão só pode editar cotações com vistoria vinculada.");
            portalUserService.assertSupervisionInspectionAccess(username, actorRole, inspection.getId());
        } else if (actorRole != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Este usuário não possui permissão para editar dados da cotação.");
        }

        assertEditableForActor(inspection, actorRole);
        assertNotExpiredWithoutPreservedFiles(quotation, inspection);

        String old = editableDataSummary(quotation.getCustomerName(), quotation.getCustomerCpf(), quotation.getWhatsapp(), quotation.getPlate(),
                quotation.getModel(), quotation.getManufactureYear(), quotation.isZeroKm(), quotation.getObservation(),
                inspection == null ? null : inspection.getResidenceAddress());

        String normalizedCpf = normalizeOptionalCpf(request.customerCpf());
        quotation.updateNonPricingData(
                request.customerName(), normalizedCpf, request.whatsapp(), request.plate(),
                request.model(), request.modelYear(), Boolean.TRUE.equals(request.zeroKm()), request.observation()
        );
        if (inspection != null) {
            if (normalizedCpf == null) {
                throw new IllegalArgumentException("Informe o CPF do associado para manter a vistoria vinculada sincronizada.");
            }
            inspection.updateEditableAssociateVehicleData(
                    request.customerName(), normalizedCpf, request.whatsapp(), request.plate(),
                    request.model(), request.modelYear(), Boolean.TRUE.equals(request.zeroKm()), inspection.getResidenceAddress()
            );
            inspectionRepository.saveAndFlush(inspection);
        }
        quotationRepository.saveAndFlush(quotation);

        String updated = editableDataSummary(quotation.getCustomerName(), quotation.getCustomerCpf(), quotation.getWhatsapp(), quotation.getPlate(),
                quotation.getModel(), quotation.getManufactureYear(), quotation.isZeroKm(), quotation.getObservation(),
                inspection == null ? null : inspection.getResidenceAddress());
        auditRepository.save(CatalogChangeAudit.createText(
                "QUOTE_EDITABLE_DATA", null, id.toString(),
                "Dados cadastrais/veículo da cotação " + quotation.getQuoteNumber() + " atualizados",
                old, updated, username
        ));
        return toQuote(quotation);
    }

    @Transactional
    public AdminQuoteResponse updateQuoteStatus(UUID id, UpdateQuoteStatusRequest request, String username) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cotação não encontrada."));
        String old = quoteAnalysisSummary(quotation);
        quotation.adminReview(request.status(), request.adminNote());
        quotationRepository.saveAndFlush(quotation);

        // Uma cotação feita pelo site pode ser aceita enquanto a distribuição automática
        // estiver desligada. Assim que houver responsável definido, o próprio Admin pode
        // concluir a análise e a vistoria é criada sem exigir uma nova ação do cliente.
        if (quotation.getOrigin() == QuoteOrigin.SELF_SERVICE
                && quotation.getStatus() == QuoteStatus.ACCEPTED
                && quotation.getConsultant() != null
                && inspectionRepository.findByQuotation_Id(quotation.getId()).isEmpty()) {
            retratoService.ensureForSelfServiceQuote(quotation);
        }

        auditRepository.save(CatalogChangeAudit.createText(
                "QUOTE_STATUS", null, id.toString(), "Cotação " + quotation.getQuoteNumber() + " analisada",
                old, quoteAnalysisSummary(quotation), username
        ));
        return toQuote(quotation);
    }

    @Transactional
    public void deleteQuote(UUID id, String username) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cotação não encontrada."));
        String number = quotation.getQuoteNumber();
        String old = "cliente=" + quotation.getCustomerName() + "; status=" + quotation.getStatus();
        quotationRepository.delete(quotation);
        quotationRepository.flush();
        auditRepository.save(CatalogChangeAudit.createText(
                "QUOTE_DELETE", null, id.toString(), "Cotação " + number + " excluída do banco",
                old, "registro excluído", username
        ));
    }

    @Transactional
    public DeleteSummary deleteAllQuotes(String username) {
        int deleted = quotationRepository.deleteAllQuotations();
        auditRepository.save(CatalogChangeAudit.createText(
                "QUOTE_DELETE", null, "BULK", "Exclusão administrativa de todas as cotações",
                "registros existentes", "excluídas=" + deleted, username
        ));
        return new DeleteSummary(deleted, 0, deleted + " cotação(ões) excluída(s) do banco.");
    }

    @Transactional(readOnly = true)
    public List<AdminInspectionResponse> inspections() {
        // O Admin pode editar os dados em qualquer status, então recebe o CPF completo
        // no payload administrativo. A exibição mascarada continua sendo decisão do frontend.
        return inspectionRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(item -> toInspection(item, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminInspectionResponse> inspectionsForAnalysis(String username, PortalRole role) {
        if (role == PortalRole.ADMIN) {
            return inspectionRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(this::isOpenForAnalysisTeam)
                    .map(item -> toInspection(item, true))
                    .toList();
        }
        UUID analystId = portalUserService.linkedAnalystId(username).orElse(null);
        return inspectionRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(this::isOpenForAnalysisTeam)
                .filter(item -> belongsToAnalystQueue(item, analystId))
                .map(item -> toInspection(item, true))
                .toList();
    }

    /**
     * Compatibilidade com vistorias históricas: ANALYST_QUEUE/ANALYST_PENDING são
     * sempre editáveis pelo Cadastro. Também aceita registros legados ainda abertos
     * que tenham ficado com uma etapa inconsistente antes das migrations atuais.
     */
    private boolean isOpenForAnalysisTeam(InspectionRequest item) {
        if (item == null) return false;
        InspectionAnalysisStage stage = item.getAnalysisStage();
        if (stage == InspectionAnalysisStage.ANALYST_QUEUE || stage == InspectionAnalysisStage.ANALYST_PENDING) {
            return true;
        }
        // A decisão da Supervisão é final. O acompanhamento do link e do aceite
        // volta ao analista responsável, sem conceder a ele poder de aprovação.
        if (stage == InspectionAnalysisStage.FINISHED) {
            return (item.getStatus() == InspectionRequestStatus.APPROVED)
                    || (item.getStatus() == InspectionRequestStatus.REJECTED && item.getDecisionMessageSentAt() == null);
        }
        InspectionRequestStatus status = item.getStatus();
        return item.getRegistrationCompletedAt() == null
                && status != InspectionRequestStatus.APPROVED
                && status != InspectionRequestStatus.REJECTED
                && status != InspectionRequestStatus.CANCELLED
                && status != InspectionRequestStatus.EXPIRED;
    }

    private boolean belongsToAnalystQueue(InspectionRequest item, UUID analystId) {
        Consultant assigned = item == null ? null : item.getAssignedAnalyst();
        if (analystId == null) return assigned == null;
        if (assigned != null && analystId.equals(assigned.getId())) return true;

        // Para históricos sem responsável válido, respeita o vínculo ATUAL
        // consultor -> analista, sem tomar de outro analista ativo uma vistoria válida.
        boolean storedAssignmentUsable = assigned != null
                && assigned.isActive()
                && assigned.getRole() == CollaboratorRole.ANALYST;
        if (storedAssignmentUsable) return false;

        Consultant consultant = item.getConsultant();
        Consultant currentAnalyst = consultant == null ? null : consultant.getAssignedAnalyst();
        return currentAnalyst != null && currentAnalyst.isActive()
                && currentAnalyst.getRole() == CollaboratorRole.ANALYST
                && analystId.equals(currentAnalyst.getId());
    }

    @Transactional(readOnly = true)
    public List<AdminInspectionResponse> inspectionsForSupervision() {
        return inspectionRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(item -> item.getAnalysisStage() == InspectionAnalysisStage.ANALYST_QUEUE
                        || item.getAnalysisStage() == InspectionAnalysisStage.ANALYST_PENDING
                        || item.getAnalysisStage() == InspectionAnalysisStage.SUPERVISION_QUEUE
                        // Inclui decisões finais atuais e históricas mesmo quando a versão
                        // antiga não gravou reviewedByRole/FINISHED de forma padronizada.
                        || item.getStatus() == InspectionRequestStatus.APPROVED
                        || item.getStatus() == InspectionRequestStatus.REJECTED)
                .map(item -> toInspection(item, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminInspectionResponse inspectionForContractEdit(
            UUID id, String username, PortalRole actorRole
    ) {
        assertContractPricingAccess(id, username, actorRole);
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertDigitalAcceptanceNotFinalized(inspection);
        return toInspection(inspection, true);
    }

    @Transactional
    public AdminInspectionResponse updateInspectionDetails(
            UUID id, UpdateInspectionDetailsRequest request, String username, PortalRole actorRole
    ) {
        if (actorRole == PortalRole.ANALYST) {
            portalUserService.assertAnalysisInspectionAccess(username, actorRole, id);
        } else if (actorRole == PortalRole.SUPERVISION_ANALYSIS) {
            portalUserService.assertSupervisionInspectionAccess(username, actorRole, id);
        } else if (actorRole != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Este usuário não possui permissão para editar os dados da vistoria.");
        }

        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertEditableForActor(inspection, actorRole);

        Quotation quotation = inspection.getQuotation();
        assertNotExpiredWithoutPreservedFilesUnlessApprovedEditable(quotation, inspection, actorRole);
        boolean oldZeroKm = quotation != null ? quotation.isZeroKm() : (inspection.getPlate() == null || inspection.getPlate().isBlank());
        String old = editableDataSummary(inspection.getAssociateName(), inspection.getCpf(), inspection.getWhatsapp(), inspection.getPlate(),
                inspection.getVehicleModel(), inspection.getModelYear(), oldZeroKm,
                quotation == null ? null : quotation.getObservation(), inspection.getResidenceAddress());

        boolean zeroKm = Boolean.TRUE.equals(request.zeroKm());
        String normalizedCpf = normalizeRequiredCpf(request.cpf());
        inspection.updateEditableAssociateVehicleData(
                request.associateName(), normalizedCpf, request.whatsapp(), request.plate(),
                request.model(), request.modelYear(), zeroKm, request.residenceAddress()
        );
        inspectionRepository.saveAndFlush(inspection);

        if (quotation != null) {
            // A identidade FIPE só é válida para o modelo, ano-modelo e condição 0 km
            // consultados. Uma correção nesses campos precisa invalidar o código antigo,
            // mas não altera os valores comerciais até que sejam revisados e salvos.
            boolean vehicleFipeIdentityChanged = !java.util.Objects.equals(
                    quotation.getModel(), request.model())
                    || !java.util.Objects.equals(quotation.getManufactureYear(), request.modelYear())
                    || quotation.isZeroKm() != zeroKm;
            quotation.updateNonPricingData(
                    request.associateName(), normalizedCpf, request.whatsapp(), request.plate(),
                    request.model(), request.modelYear(), zeroKm, quotation.getObservation()
            );
            if (vehicleFipeIdentityChanged) {
                quotation.updateFipeMetadata(null, null, null);
                fipeSelectionService.drop(inspection.getId());
            }
            quotationRepository.saveAndFlush(quotation);
        }
        refreshInspectionDossierAfterEdit(inspection);

        String updated = editableDataSummary(inspection.getAssociateName(), inspection.getCpf(), inspection.getWhatsapp(), inspection.getPlate(),
                inspection.getVehicleModel(), inspection.getModelYear(), zeroKm,
                quotation == null ? null : quotation.getObservation(), inspection.getResidenceAddress());
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_EDITABLE_DATA", null, id.toString(),
                "Dados cadastrais/veículo da vistoria de " + inspection.getAssociateName() + " atualizados",
                old, updated, username
        ));
        return toInspection(inspection, true);
    }

    @Transactional(readOnly = true)
    public CommercialPricingPreviewResponse previewInspectionContractPricing(
            UUID id, CommercialPricingPreviewRequest request, String username, PortalRole actorRole
    ) {
        assertContractPricingAccess(id, username, actorRole);
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        Quotation quotation = inspection.getQuotation();
        if (quotation == null) {
            throw new IllegalArgumentException("Esta vistoria não possui uma cotação vinculada para calcular o contrato.");
        }

        BigDecimal requestedFipe = request.fipeValue().setScale(2, RoundingMode.HALF_UP);
        int requestedDiscount = request.discountPercent() == null ? 0 : request.discountPercent();
        Set<String> requestedBenefits = normalizeRequestedBenefits(
                quotation, requestedFipe, requestedDiscount, request.benefitCodes()
        );
        var pricing = commercialPricingService.calculate(quotation, requestedFipe, requestedDiscount, requestedBenefits);
        return new CommercialPricingPreviewResponse(
                pricing.planBaseMonthlyValue(), pricing.mandatoryMonthlyFee(), pricing.oneTimeFee(),
                pricing.mandatoryFeeDescription(), pricing.optionalsMonthlyValue(), pricing.subtotalBeforeDiscount(),
                pricing.discountValue(), pricing.finalMonthlyValue(), pricing.catalogBased()
        );
    }

    @Transactional
    public AdminInspectionResponse updateInspectionContractValues(
            UUID id, UpdateInspectionContractValuesRequest request, String username, PortalRole actorRole
    ) {
        assertContractPricingAccess(id, username, actorRole);

        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertContractValuesEditable(inspection, actorRole);

        Quotation quotation = inspection.getQuotation();
        if (quotation == null) {
            throw new IllegalArgumentException("Esta vistoria não possui uma cotação vinculada para alterar o contrato.");
        }
        assertNotExpiredWithoutPreservedFilesUnlessApprovedEditable(quotation, inspection, actorRole);

        BigDecimal requestedFipe = request.fipeValue().setScale(2, RoundingMode.HALF_UP);
        var selectedFipeVersion = fipeSelectionService.verify(request.fipeRecordId(), inspection, quotation, requestedFipe);
        int requestedDiscount = request.discountPercent() == null ? 0 : request.discountPercent();
        RearWindowBranding requestedBranding = validateDiscountAndBranding(
                quotation, requestedFipe, requestedDiscount, request.rearWindowBranding()
        );
        Set<String> requestedBenefits = normalizeRequestedBenefits(
                quotation, requestedFipe, requestedDiscount, request.benefitCodes()
        );

        // A fonte padrão do novo valor é sempre a tabela vigente do plano para a
        // FIPE atual + somente as taxas obrigatórias que ainda se aplicam +
        // adicionais selecionados. A mensalidade histórica não entra no cálculo.
        var pricing = commercialPricingService.calculate(quotation, requestedFipe, requestedDiscount, requestedBenefits);
        if (Boolean.TRUE.equals(request.manualMonthlyOverride())) {
            throw new IllegalArgumentException(
                    "O valor mensal final é calculado automaticamente pela tabela do plano, FIPE, rastreador/taxas, adicionais e desconto."
            );
        }
        boolean manualMonthlyOverride = false;
        BigDecimal requestedMonthly = pricing.finalMonthlyValue();

        BigDecimal currentFipe = quotation.getFipeValue().setScale(2, RoundingMode.HALF_UP);
        BigDecimal currentMonthly = quotation.getMonthlyValue().setScale(2, RoundingMode.HALF_UP);
        int currentDiscount = quotation.getDiscountPercent() == null ? 0 : quotation.getDiscountPercent();
        RearWindowBranding currentBranding = quotation.getRearWindowBranding() == null
                ? RearWindowBranding.NOT_APPLICABLE : quotation.getRearWindowBranding();
        Set<String> currentBenefits = normalizedFinalBenefits(quotation, currentFipe, currentDiscount);

        boolean fipeChanged = currentFipe.compareTo(requestedFipe) != 0;
        boolean monthlyChanged = currentMonthly.compareTo(requestedMonthly) != 0;
        boolean discountChanged = currentDiscount != requestedDiscount;
        boolean brandingChanged = currentBranding != requestedBranding;
        boolean benefitsChanged = !currentBenefits.equals(requestedBenefits);

        if (!fipeChanged && !monthlyChanged && !discountChanged && !brandingChanged && !benefitsChanged
                && selectedFipeVersion == null && !inspection.hasPendingContractChange()) {
            return toInspection(inspection, true);
        }

        String old = contractRevisionSummary(currentFipe, currentMonthly, currentDiscount, currentBranding, currentBenefits);
        boolean approvedAwaitingDigitalAcceptance = inspection.getStatus() == InspectionRequestStatus.APPROVED
                && inspection.getAcceptedAt() == null
                && (actorRole == PortalRole.ADMIN
                    || actorRole == PortalRole.SUPERVISION_ANALYSIS
                    || actorRole == PortalRole.CONSULTANT);
        boolean needsAssociateConfirmation = !approvedAwaitingDigitalAcceptance
                && (monthlyChanged || discountChanged || brandingChanged);
        if (needsAssociateConfirmation) {
            inspection.requestContractChange(
                    requestedFipe, requestedMonthly, requestedDiscount, requestedBranding, requestedBenefits,
                    manualMonthlyOverride, username
            );
            inspectionRepository.flush();
            fipeSelectionService.stage(inspection.getId(), request.fipeRecordId());
            String updated = "PROPOSTA PENDENTE; "
                    + contractRevisionSummary(requestedFipe, requestedMonthly, requestedDiscount, requestedBranding, requestedBenefits);
            auditRepository.save(CatalogChangeAudit.createText(
                    "INSPECTION_CONTRACT_CHANGE_REQUEST", null, id.toString(),
                    "Revisão comercial aguardando confirmação do associado",
                    old, updated, username
            ));
            return toInspection(inspection, true);
        }

        // Antes de consolidar, sincroniza serviços adicionais com o catálogo
        // vigente. Assim inclusão/remoção usa sempre o preço cadastrado agora.
        commercialPricingService.synchronizeSelectedOptionals(quotation, requestedBenefits);

        // FIPE e benefícios podem ser saneados no dossiê final sem alterar o preço
        // aceito. Regras automáticas continuam prevalecendo dentro da entidade.
        quotation.applyCommercialRevision(
                requestedFipe, requestedMonthly, requestedDiscount, requestedBranding, requestedBenefits,
                pricing.planBaseMonthlyValue(), pricing.mandatoryMonthlyFee(), pricing.oneTimeFee(),
                pricing.mandatoryFeeDescription(), manualMonthlyOverride
        );
        if (selectedFipeVersion != null || fipeChanged) {
            fipeSelectionService.apply(quotation, selectedFipeVersion);
        }
        fipeSelectionService.drop(inspection.getId());
        quotationRepository.saveAndFlush(quotation);
        if (inspection.hasPendingContractChange()) {
            inspection.clearPendingContractChange();
            inspectionRepository.flush();
        }
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_CONTRACT_VALUES", null, id.toString(),
                "Dossiê comercial da vistoria de " + inspection.getAssociateName() + " revisado",
                old, contractRevisionSummary(quotation.getFipeValue(), quotation.getMonthlyValue(),
                        quotation.getDiscountPercent(), quotation.getRearWindowBranding(), quotation.finalSelectedCoverageCodes()),
                username
        ));
        refreshInspectionDossierAfterEdit(inspection);
        return toInspection(inspection, true);
    }

    @Transactional
    public AdminInspectionResponse updateSupervisionNote(
            UUID id, String note, String username, PortalRole actorRole
    ) {
        if (actorRole != PortalRole.SUPERVISION_ANALYSIS && actorRole != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Este usuário não possui permissão para registrar O.B.S. da Supervisão.");
        }
        portalUserService.assertSupervisionInspectionAccess(username, actorRole, id);
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertDigitalAcceptanceNotFinalized(inspection);

        String supervisorName = ADMIN_RESPONSIBLE_NAME;
        if (actorRole == PortalRole.SUPERVISION_ANALYSIS) {
            UUID supervisorId = portalUserService.linkedSupervisorId(username)
                    .orElseThrow(() -> new IllegalArgumentException("Este usuário de supervisão não está vinculado a um colaborador."));
            supervisorName = consultantService.findActiveSupervisor(supervisorId).getName();
        }

        String oldNote = inspection.getSupervisionNote();
        inspection.updateSupervisionNote(note, supervisorName);
        inspectionRepository.flush();
        refreshInspectionDossierAfterEdit(inspection);
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_SUPERVISION_NOTE", null, id.toString(),
                "O.B.S. da Supervisão atualizada por " + supervisorName,
                oldNote == null ? "sem observação" : oldNote,
                inspection.getSupervisionNote() == null ? "sem observação" : inspection.getSupervisionNote(),
                username
        ));
        return toInspection(inspection, true);
    }

    @Transactional
    public AdminInspectionResponse markRegistrationCompleted(UUID id, String note, String username, PortalRole actorRole) {
        if (actorRole != PortalRole.ANALYST
                && actorRole != PortalRole.ADMIN
                && actorRole != PortalRole.SUPERVISION_ANALYSIS) {
            throw new IllegalArgumentException("Este usuário não possui permissão para concluir o cadastro da vistoria.");
        }
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertDigitalAcceptanceNotFinalized(inspection);
        // ADM e Supervisão podem concluir a etapa cadastral mesmo quando existe
        // uma alteração comercial pendente. A confirmação comercial continua
        // bloqueando a decisão final, mas não deve impedir o estado "Cadastro realizado".
        // O analista mantém a regra restritiva original.
        if (inspection.hasPendingContractChange() && actorRole == PortalRole.ANALYST) {
            throw new IllegalArgumentException(
                    "Existe uma alteração de FIPE/mensalidade aguardando confirmação do associado. Envie o link e aguarde a resposta antes de marcar Cadastro feito."
            );
        }
        String old = inspectionAnalysisSummary(inspection);
        String reviewerName;
        if (actorRole == PortalRole.ADMIN) {
            reviewerName = ADMIN_RESPONSIBLE_NAME;
            inspection.markRegistrationCompletedByAdministrator(reviewerName, note);
        } else if (actorRole == PortalRole.SUPERVISION_ANALYSIS) {
            portalUserService.assertSupervisionInspectionAccess(username, actorRole, id);
            UUID supervisorId = portalUserService.linkedSupervisorId(username)
                    .orElseThrow(() -> new IllegalArgumentException("Este usuário de supervisão não está vinculado a um colaborador."));
            Consultant supervisor = consultantService.findActiveSupervisor(supervisorId);
            reviewerName = supervisor.getName();
            inspection.markRegistrationCompletedBySupervisor(reviewerName, note);
        } else {
            UUID analystId = portalUserService.linkedAnalystId(username)
                    .orElseThrow(() -> new IllegalArgumentException("Este usuário de análise não está vinculado a um analista específico."));
            Consultant analyst = consultantService.findActiveAnalyst(analystId);
            reviewerName = analyst.getName();
            inspection.markRegistrationCompleted(analyst, note);
        }
        inspectionRepository.flush();
        refreshInspectionDossierAfterEdit(inspection);
        boolean finalDecisionPreserved = inspection.getAnalysisStage() == InspectionAnalysisStage.FINISHED
                && (inspection.getStatus() == InspectionRequestStatus.APPROVED
                || inspection.getStatus() == InspectionRequestStatus.REJECTED);
        String source = finalDecisionPreserved
                ? "Cadastro regularizado por " + reviewerName + " sem remover a decisão final já registrada"
                : (actorRole == PortalRole.SUPERVISION_ANALYSIS
                    ? "Cadastro assumido pela Supervisão por " + reviewerName
                    : "Cadastro concluído por " + reviewerName + " e enviado à Supervisão de Análise");
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_REGISTRATION", null, id.toString(),
                source,
                old, inspectionAnalysisSummary(inspection) + "; etapa=" + inspection.getAnalysisStage(), username
        ));
        return toInspection(inspection, true);
    }

    @Transactional
    public AdminInspectionResponse markRegistrationNotCompleted(UUID id, String note, String username, PortalRole actorRole) {
        if (actorRole != PortalRole.ANALYST
                && actorRole != PortalRole.ADMIN
                && actorRole != PortalRole.SUPERVISION_ANALYSIS) {
            throw new IllegalArgumentException("Este usuário não possui permissão para marcar Cadastro não realizado.");
        }
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertDigitalAcceptanceNotFinalized(inspection);
        String old = inspectionAnalysisSummary(inspection);
        String reviewerName;
        if (actorRole == PortalRole.ADMIN) {
            reviewerName = ADMIN_RESPONSIBLE_NAME;
            inspection.markRegistrationNotCompletedByAdministrator(reviewerName, note);
        } else if (actorRole == PortalRole.SUPERVISION_ANALYSIS) {
            portalUserService.assertSupervisionInspectionAccess(username, actorRole, id);
            UUID supervisorId = portalUserService.linkedSupervisorId(username)
                    .orElseThrow(() -> new IllegalArgumentException("Este usuário de supervisão não está vinculado a um colaborador."));
            Consultant supervisor = consultantService.findActiveSupervisor(supervisorId);
            reviewerName = supervisor.getName();
            inspection.markRegistrationNotCompletedBySupervisor(reviewerName, note);
        } else {
            UUID analystId = portalUserService.linkedAnalystId(username)
                    .orElseThrow(() -> new IllegalArgumentException("Este usuário de análise não está vinculado a um analista específico."));
            Consultant analyst = consultantService.findActiveAnalyst(analystId);
            reviewerName = analyst.getName();
            inspection.markRegistrationNotCompleted(analyst, note);
        }
        inspectionRepository.flush();
        refreshInspectionDossierAfterEdit(inspection);
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_REGISTRATION", null, id.toString(),
                "Cadastro marcado como não realizado por " + reviewerName,
                old, inspectionAnalysisSummary(inspection) + "; etapa=" + inspection.getAnalysisStage()
                        + "; situação=CADASTRO_NAO_REALIZADO", username
        ));
        return toInspection(inspection, true);
    }

    /** A supervisão pode transferir o acompanhamento para quem realizou o cadastro,
     * sem modificar a aprovação, o plano ou a assinatura do associado. */
    @Transactional
    public AdminInspectionResponse reassignInspectionAnalyst(UUID id, UUID newAnalystId, String username, PortalRole role) {
        portalUserService.assertSupervisionInspectionAccess(username, role, id);
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Vistoria não encontrada."));
        assertDigitalAcceptanceNotFinalized(inspection);
        if (inspection.getStatus() == InspectionRequestStatus.CANCELLED
                || inspection.getStatus() == InspectionRequestStatus.EXPIRED) {
            throw new IllegalArgumentException("Não é possível redistribuir uma vistoria cancelada ou expirada.");
        }
        Consultant newAnalyst = consultantService.findActiveAnalyst(newAnalystId);
        String oldAnalyst = inspection.getAssignedAnalystName();
        if (inspection.getAssignedAnalyst() != null && inspection.getAssignedAnalyst().getId().equals(newAnalystId)) {
            return toInspection(inspection, true);
        }
        inspection.assignAnalyst(newAnalyst);
        inspectionRepository.flush();
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_ANALYST_ASSIGNMENT", null, id.toString(),
                "Redistribuição do cadastro e acompanhamento do aceite pela Supervisão",
                oldAnalyst, newAnalyst.getName(), username
        ));
        return toInspection(inspection, true);
    }

    @Transactional
    public AdminInspectionResponse markDecisionMessageSent(UUID id) {
        return markDecisionMessageSent(id, false);
    }

    @Transactional
    public AdminInspectionResponse markDecisionMessageSentForAnalysis(UUID id, String username, PortalRole role) {
        portalUserService.assertAnalysisInspectionAccess(username, role, id);
        return markDecisionMessageSent(id, true);
    }

    @Transactional
    public AdminInspectionResponse markDecisionMessageSentForSupervision(UUID id, String username, PortalRole role) {
        portalUserService.assertSupervisionInspectionAccess(username, role, id);
        return markDecisionMessageSent(id, true);
    }

    private AdminInspectionResponse markDecisionMessageSent(UUID id, boolean revealCpf) {
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        String destination = inspection.getStatus() == InspectionRequestStatus.APPROVED
                ? normalizeAssociatePhone(inspection.getWhatsapp())
                : null;
        inspection.markDecisionMessageSent(destination);
        inspectionRepository.flush();
        return toInspection(inspection, revealCpf);
    }

    @Transactional
    public AdminInspectionResponse updateInspectionStatus(UUID id, UpdateInspectionStatusRequest request, String username) {
        return updateInspectionStatus(id, request, username, PortalRole.ADMIN, false);
    }

    @Transactional
    public AdminInspectionResponse updateInspectionStatusForAnalysis(
            UUID id,
            UpdateInspectionStatusRequest request,
            String username,
            PortalRole actorRole
    ) {
        portalUserService.assertAnalysisInspectionAccess(username, actorRole, id);
        return updateInspectionStatus(id, request, username, actorRole, true);
    }

    @Transactional
    public AdminInspectionResponse updateInspectionStatusForSupervision(
            UUID id, UpdateInspectionStatusRequest request, String username, PortalRole actorRole
    ) {
        if (actorRole != PortalRole.SUPERVISION_ANALYSIS && actorRole != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Este usuário não possui permissão para supervisionar vistorias.");
        }
        if (request.status() != InspectionRequestStatus.APPROVED && request.status() != InspectionRequestStatus.REJECTED) {
            throw new IllegalArgumentException("A Supervisão de Análise pode aprovar ou rejeitar a vistoria.");
        }
        if (request.adminNote() == null || request.adminNote().isBlank()) {
            throw new IllegalArgumentException("Informe uma observação explicando o motivo da aprovação ou rejeição.");
        }
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertDigitalAcceptanceNotFinalized(inspection);
        boolean firstDecision = inspection.getAnalysisStage() == InspectionAnalysisStage.SUPERVISION_QUEUE;
        boolean revisableFinalDecision = inspection.getStatus() == InspectionRequestStatus.APPROVED
                || inspection.getStatus() == InspectionRequestStatus.REJECTED;
        if (!firstDecision && !revisableFinalDecision) {
            throw new IllegalArgumentException(
                    "Esta vistoria ainda não está disponível para decisão da Supervisão."
            );
        }
        if (inspection.hasPendingContractChange()) {
            throw new IllegalArgumentException(
                    "Existe uma alteração de FIPE/mensalidade aguardando confirmação do associado. Envie o link de confirmação e aguarde a resposta antes da decisão final."
            );
        }
        Consultant supervisorCollaborator = null;
        String reviewerName = ADMIN_RESPONSIBLE_NAME;
        String reviewerRole = "ADMIN_SUPERVISION";
        if (actorRole == PortalRole.SUPERVISION_ANALYSIS) {
            UUID supervisorId = portalUserService.linkedSupervisorId(username)
                    .orElseThrow(() -> new IllegalArgumentException("Este usuário de supervisão não está vinculado a um colaborador."));
            supervisorCollaborator = consultantService.findActiveSupervisor(supervisorId);
            reviewerName = supervisorCollaborator.getName();
            reviewerRole = "SUPERVISION_ANALYSIS";
        }
        String old = inspectionAnalysisSummary(inspection);
        if (revisableFinalDecision) {
            inspection.invalidatePendingDigitalAcceptance();
        }
        // A entrada em SUPERVISION_QUEUE já comprova que os requisitos foram conferidos
        // no momento do Cadastro feito. A decisão continua disponível enquanto a vistoria
        // estiver disponível no armazenamento. Não existe mais exclusão geral por 40 dias.
        inspection.adminReview(
                request.status(), request.adminNote(), supervisorCollaborator, reviewerName, reviewerRole,
                true
        );
        inspectionRepository.flush();
        persistFinalInspectionDossier(inspection);
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_SUPERVISION", null, id.toString(),
                "Supervisão da vistoria de " + inspection.getAssociateName() + " por " + reviewerName,
                old, inspectionAnalysisSummary(inspection), username
        ));
        return toInspection(inspection, true);
    }

    private AdminInspectionResponse updateInspectionStatus(
            UUID id,
            UpdateInspectionStatusRequest request,
            String username,
            PortalRole actorRole,
            boolean revealCpf
    ) {
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        assertDigitalAcceptanceNotFinalized(inspection);
        String old = inspectionAnalysisSummary(inspection);

        Consultant reviewerCollaborator = null;
        String reviewerName;
        String reviewerRole;
        if (actorRole == PortalRole.ANALYST
                && request.status() != InspectionRequestStatus.UNDER_REVIEW
                && request.status() != InspectionRequestStatus.WAITING_FILES
                && request.status() != InspectionRequestStatus.UPLOADING_FILES) {
            throw new IllegalArgumentException("A Equipe de Análise trabalha somente com Cadastro feito, Cadastro não feito e Aguardando documentos. A decisão final pertence à Supervisão de Análise.");
        }
        if (actorRole == PortalRole.ADMIN
                && (request.status() == InspectionRequestStatus.APPROVED || request.status() == InspectionRequestStatus.REJECTED)) {
            throw new IllegalArgumentException("A aprovação ou rejeição final deve ser feita pelos controles de Supervisão após o Cadastro feito.");
        }
        if (actorRole == PortalRole.ADMIN) {
            reviewerName = ADMIN_RESPONSIBLE_NAME;
            reviewerRole = "ADMIN_ANALYSIS";
        } else if (actorRole == PortalRole.ANALYST) {
            reviewerCollaborator = resolveAnalystReviewer(request, username);
            reviewerName = reviewerCollaborator.getName();
            reviewerRole = "ANALYST";
        } else {
            throw new IllegalArgumentException("Este usuário não possui permissão para analisar vistorias.");
        }

        inspection.adminReview(
                request.status(), request.adminNote(), reviewerCollaborator, reviewerName, reviewerRole,
                actorRole == PortalRole.ADMIN
        );
        inspectionRepository.flush();
        if (request.status() == InspectionRequestStatus.APPROVED || request.status() == InspectionRequestStatus.REJECTED) {
            persistFinalInspectionDossier(inspection);
        }
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_STATUS", null, id.toString(),
                "Retrato NH de " + inspection.getAssociateName() + " analisado por " + reviewerName,
                old, inspectionAnalysisSummary(inspection), username
        ));
        return toInspection(inspection, revealCpf);
    }

    private void refreshNonFinalInspectionReportIfPresent(InspectionRequest inspection) {
        InspectionAsset report = inspection.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                .findFirst()
                .orElse(null);
        if (report == null) return;
        byte[] reportBytes = retratoPdfService.generate(inspection);
        storageService.replaceGeneratedReport(
                inspection,
                "Relatório da vistoria atualizado",
                "relatorio-retrato-nh.pdf",
                report.getSortOrder(),
                reportBytes
        );
        inspectionRepository.flush();
    }

    private void persistFinalInspectionDossier(InspectionRequest inspection) {
        byte[] finalReport = retratoPdfService.generate(inspection);
        int reportOrder = inspection.getAssets().stream()
                .filter(asset -> asset.getAssetType() == InspectionAssetType.REPORT)
                .mapToInt(InspectionAsset::getSortOrder)
                .findFirst()
                .orElseGet(() -> inspection.getRequestType() == InspectionRequestType.NEW_INSPECTION
                        ? inspection.getVehicleType().requiredPhotoCount() + 6
                        : 2);

        storageService.replaceGeneratedReport(
                inspection,
                "Dossiê final da vistoria",
                "dossie-final-vistoria-" + inspection.getId() + ".pdf",
                reportOrder,
                finalReport
        );
        inspectionRepository.flush();
    }

    private Consultant resolveAnalystReviewer(UpdateInspectionStatusRequest request, String username) {
        var linkedAnalyst = portalUserService.linkedAnalystId(username);
        if (linkedAnalyst.isPresent()) {
            return consultantService.findActiveAnalyst(linkedAnalyst.get());
        }
        if (request.analystId() != null) {
            return consultantService.findActiveAnalyst(request.analystId());
        }
        if (request.analystName() != null && !request.analystName().isBlank()) {
            var created = consultantService.create(
                    request.analystName(), CollaboratorRole.ANALYST, "CREATED_IN_ANALYSIS", username
            );
            return consultantService.findActiveAnalyst(created.id());
        }
        throw new IllegalArgumentException("Selecione o analista responsável ou informe o nome de um novo analista.");
    }

    @Transactional
    public void deleteInspection(UUID id, String username) {
        InspectionRequest inspection = inspectionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solicitação do Retrato NH não encontrada."));
        String old = "associado=" + inspection.getAssociateName() + "; placa=" + plateLabel(inspection.getPlate(), false)
                + "; status=" + inspection.getStatus();
        inspectionRepository.delete(inspection);
        inspectionRepository.flush();
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_DELETE", null, id.toString(), "Retrato NH excluído do banco",
                old, "registro e arquivos excluídos", username
        ));
    }

    @Transactional
    public DeleteSummary deleteAllAllowedInspections(String username) {
        int deleted = inspectionRepository.deleteAllInspections();
        auditRepository.save(CatalogChangeAudit.createText(
                "INSPECTION_DELETE", null, "BULK", "Exclusão administrativa de todas as vistorias",
                "sem proteção por status", "excluídas=" + deleted, username
        ));
        return new DeleteSummary(
                deleted, 0,
                deleted + " vistoria(s) excluída(s), independentemente do status ou de documentos pendentes."
        );
    }

    private AdminQuoteResponse toQuote(Quotation item) {
        InspectionRequest linkedInspection = inspectionRepository.findByQuotation_Id(item.getId()).orElse(null);
        boolean hasPreservedFile = linkedInspection != null && linkedInspection.hasAnyPreservedFile();
        boolean expired = item.getStatus() != QuoteStatus.CANCELLED
                && !hasPreservedFile
                && item.getValidUntil() != null
                && OffsetDateTime.now().isAfter(item.getValidUntil());
        String pdfUrl = publicApiUrl + "/api/quotes/" + item.getId() + "/pdf";
        String whatsapp = settingsService.teamWhatsapp();
        String email = settingsService.teamEmail();
        String inspectionUrl = linkedInspection == null
                ? null
                : publicWebUrl + "/retrato/?token=" + linkedInspection.getPublicToken();
        // A área /api/admin/quotes é do Administrador. O Admin pode corrigir
        // dados cadastrais do associado/veículo em qualquer etapa; a FIPE não
        // faz parte do DTO de edição e continua imutável por esta operação.
        boolean detailsEditable = true;
        String message = "Cotação " + item.getQuoteNumber()
                + "\nCliente: " + item.getCustomerName()
                + "\nOrigem: " + (item.getOrigin() == QuoteOrigin.SELF_SERVICE ? "Cliente pelo site" : "Consultor")
                + "\nResponsável: " + item.getConsultantName()
                + "\nPlaca: " + plateLabel(item.getPlate(), item.isZeroKm())
                + "\nPlano: " + item.getSelectedPlanName()
                + "\nPDF: " + pdfUrl;
        String subject = "Cotação " + item.getQuoteNumber() + " - " + TenantIdentity.displayName() + "";
        return new AdminQuoteResponse(
                item.getId(), item.getQuoteNumber(), item.getOrigin(),
                item.getConsultant() == null ? null : item.getConsultant().getId(),
                item.getConsultantName(), item.getCustomerName(), maskCpf(item.getCustomerCpf()), formatCpf(item.getCustomerCpf()), item.getWhatsapp(),
                item.getPlate(), item.getModel(), item.getManufactureYear(), item.isZeroKm(), detailsEditable, item.getFipeValue(),
                item.getAuctionOrChassisRemarked(), item.getIndemnityFipePercent(),
                item.getCategoryCode(), item.getRegion(), item.getMotorcycleOrigin(), item.getMotorcycleCc(), item.getObservation(), item.getSelectedPlanName(),
                item.getPreDiscountMonthlyValue(), item.getDiscountPercent(), item.getRearWindowBranding(), item.getMonthlyValue(), item.getOneTimeFee(),
                item.getStatus(), item.getCreatedAt(), item.getUpdatedAt(), item.getValidUntil(), expired, item.getDecidedAt(), item.getAdminNote(),
                item.getReviewedAt(), pdfUrl, item.getDriveFolderUrl(), item.getDrivePdfUrl(), inspectionUrl,
                whatsappUrl(whatsapp, message), emailUrl(email, subject, message)
        );
    }

    private AdminInspectionResponse toInspection(InspectionRequest item) {
        return toInspection(item, false);
    }

    private AdminInspectionResponse toInspection(InspectionRequest item, boolean revealCpf) {
        String publicUrl = publicWebUrl + "/retrato/?token=" + item.getPublicToken();
        String quotationPdfUrl = item.getQuotation() == null
                ? null
                : publicApiUrl + "/api/quotes/" + item.getQuotation().getId() + "/pdf";
        String whatsapp = settingsService.teamWhatsapp();
        String email = settingsService.teamEmail();
        String type = item.getRequestType() == InspectionRequestType.NEW_INSPECTION
                ? "Nova vistoria" : "Atualização de boleto";
        String plateLabel = plateLabel(item.getPlate(), item.getRequestType() == InspectionRequestType.NEW_INSPECTION);

        List<br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse> assets = item.getAssets().stream()
                .map(asset -> new br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse(
                        asset.getId(), asset.getAssetType(), asset.getLabel(), asset.getFileName(),
                        asset.getContentType(), asset.getFileSize(), null, asset.getSortOrder(),
                        storageService.isAvailable(asset), asset.getStoredAt(), asset.getExpiresAt(), asset.getPurgedAt()
                ))
                .toList();
        int availableCount = (int) assets.stream().filter(br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse::available).count();
        int expiredCount = (int) assets.stream().filter(asset -> !asset.available() && asset.purgedAt() != null).count();
        OffsetDateTime filesExpireAt = assets.stream()
                .filter(br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse::available)
                .map(br.com.nh.cotacao.dto.InspectionDtos.InspectionAssetResponse::expiresAt)
                .filter(java.util.Objects::nonNull)
                .max(OffsetDateTime::compareTo)
                .orElse(null);

        String message = "Retrato NH - " + type
                + "\nAssociado: " + item.getAssociateName()
                + "\nConsultor: " + item.getConsultantName()
                + "\nPlaca: " + plateLabel
                + (item.getContractedPlan() == null ? "" : "\nPlano já contratado: " + item.getContractedPlan())
                + (availableCount == 0
                    ? "\nLink: " + publicUrl
                    : "\nArquivos confirmados permanecem disponíveis junto à vistoria.");
        String subject = "Retrato NH - " + plateLabel;
        InspectionRequestStatus displayStatus;
        boolean preCompletion = item.getStatus() == InspectionRequestStatus.WAITING_FILES
                || item.getStatus() == InspectionRequestStatus.UPLOADING_FILES
                || item.getStatus() == InspectionRequestStatus.CREATED;
        if (availableCount == 0 && preCompletion) {
            displayStatus = InspectionRequestStatus.WAITING_FILES;
        } else if (availableCount > 0
                && (item.getStatus() == InspectionRequestStatus.WAITING_FILES
                    || item.getStatus() == InspectionRequestStatus.CREATED)) {
            displayStatus = InspectionRequestStatus.UPLOADING_FILES;
        } else {
            displayStatus = item.getStatus();
        }
        String associateInspectionUrl = associateInspectionWhatsappUrl(item, publicUrl);
        String consultantInspectionUrl = consultantInspectionWhatsappUrl(item, publicUrl);
        String associateDecisionUrl = associateDecisionWhatsappUrl(item);
        boolean decisionMessagePending = associateDecisionUrl != null && item.getDecisionMessageSentAt() == null;

        boolean contractChangePending = item.hasPendingContractChange();
        String contractChangeConfirmationUrl = contractChangePending
                ? publicWebUrl + "/confirmacao-valor/?token=" + UriUtils.encode(item.getPublicToken(), StandardCharsets.UTF_8)
                : null;
        String contractChangeWhatsappUrl = null;
        if (contractChangePending && item.getWhatsapp() != null && !item.getWhatsapp().isBlank()) {
            String firstName = item.getAssociateName() == null || item.getAssociateName().isBlank()
                    ? "associado" : item.getAssociateName().trim().split("\\s+")[0];
            String contractMessage = "Olá, " + firstName + "! Houve uma revisão da FIPE/valor/benefícios do seu plano da " + TenantIdentity.displayName() + "."
                    + "\nPara o contrato seguir com o novo valor, confirme neste link: " + contractChangeConfirmationUrl
                    + "\nSe você já havia escolhido adicionais, o link também perguntará se deseja mantê-los.";
            contractChangeWhatsappUrl = whatsappUrl(item.getWhatsapp(), contractMessage);
        }
        Quotation quotation = item.getQuotation();
        BigDecimal effectiveFipe = contractChangePending && item.getPendingContractFipeValue() != null
                ? item.getPendingContractFipeValue() : (quotation == null ? null : quotation.getFipeValue());
        int effectiveDiscount = contractChangePending && item.getPendingContractDiscountPercent() != null
                ? item.getPendingContractDiscountPercent() : (quotation == null || quotation.getDiscountPercent() == null ? 0 : quotation.getDiscountPercent());
        Set<String> effectiveBenefits = quotation == null ? Set.of()
                : (contractChangePending && item.getPendingContractBenefitCodes() != null
                    ? normalizeStoredBenefits(quotation, effectiveFipe, effectiveDiscount, item.pendingContractBenefitCodesSet())
                    : normalizedFinalBenefits(quotation, effectiveFipe, effectiveDiscount));
        DiscountRule effectiveDiscountRule = quotation == null ? null
                : discountRuleService.findApplicableQuietly(
                        quotation, effectiveDiscount, effectiveFipe, quotation.getManufactureYear()
                ).orElse(null);
        List<CommercialDiscountResponse> commercialDiscounts = quotation == null ? List.of()
                : discountRuleService.optionsForQuotation(quotation, effectiveFipe, quotation.getManufactureYear()).stream()
                    .map(option -> {
                        DiscountRule rule = option.rule();
                        List<CommercialDiscountBenefitEffectResponse> effects = rule.getBenefitRules().stream()
                                .filter(effect -> effect.getCoverage() != null)
                                .map(effect -> new CommercialDiscountBenefitEffectResponse(
                                        effect.getCoverage().getCode(), effect.getAction(),
                                        effect.getDetailOverride(), effect.getAmountOverride()
                                ))
                                .toList();
                        return new CommercialDiscountResponse(
                                rule.getName(), rule.getPercent(), option.available(), option.unavailableReason(),
                                Boolean.TRUE.equals(rule.getRequiresRearWindowPerforation()), option.requiredBranding(),
                                option.conditionText(), Boolean.TRUE.equals(rule.getApplyToBase()),
                                Boolean.TRUE.equals(rule.getApplyToMandatoryFee()), Boolean.TRUE.equals(rule.getApplyToOptionals()), effects
                        );
                    })
                    .toList();
        List<CommercialBenefitResponse> commercialBenefits = quotation == null ? List.of()
                : commercialPricingService.benefitCatalog(quotation).stream()
                    .map(benefit -> toCommercialBenefit(
                            quotation, benefit, effectiveFipe, effectiveDiscount, effectiveDiscountRule, effectiveBenefits
                    ))
                    .toList();
        BigDecimal effectivePendingMonthly = item.getPendingContractMonthlyValue();
        if (quotation != null && contractChangePending && effectiveFipe != null) {
            // Não devolve para a tela um total pendente histórico. A segunda etapa
            // abre já com a mensalidade que o catálogo atual produz neste instante.
            effectivePendingMonthly = commercialPricingService.calculate(
                    quotation, effectiveFipe, effectiveDiscount, effectiveBenefits
            ).finalMonthlyValue();
        }
        boolean expiredWithoutFiles = !item.hasAnyPreservedFile()
                && ((item.getExpiresAt() != null && OffsetDateTime.now().isAfter(item.getExpiresAt()))
                    || (quotation != null && quotation.getValidUntil() != null && OffsetDateTime.now().isAfter(quotation.getValidUntil())));

        InspectionAcceptanceWorkflowService.Progress acceptanceProgress = acceptanceWorkflow.progress(item);
        return new AdminInspectionResponse(
                item.getId(), item.getRequestType().name(), item.getVehicleType().name(), item.getAssociateName(),
                revealCpf ? formatCpf(item.getCpf()) : maskCpf(item.getCpf()), formatCpf(item.getCpf()),
                item.getWhatsapp(), item.getPlate(),
                item.getQuotation() != null ? item.getQuotation().isZeroKm() : (item.getPlate() == null || item.getPlate().isBlank()),
                item.getVehicleModel(), item.getModelYear(),
                quotation == null ? null : quotation.getVehicleFipeCode(),
                quotation == null ? null : quotation.getVehicleFipeReference(),
                item.getResidenceAddress(), item.hasPreexistingDamage(), item.getPreexistingDamageDetails(), item.getContractedPlan(),
                item.getQuotation() == null ? null : item.getQuotation().getFipeValue(),
                item.getQuotation() == null ? null : item.getQuotation().getMonthlyValue(),
                item.getQuotation() == null ? null : item.getQuotation().getPreDiscountMonthlyValue(),
                item.getQuotation() == null ? null : item.getQuotation().getBillingDueDay(),
                item.getQuotation() == null ? null : item.getQuotation().getFirstBillingDueDate(),
                item.getQuotation() == null ? 0 : item.getQuotation().getDiscountPercent(),
                item.getQuotation() == null ? RearWindowBranding.NOT_APPLICABLE : item.getQuotation().getRearWindowBranding(),
                quotation == null ? null : quotation.getSelectedPlanName(),
                quotation == null ? null : quotation.getSelectedPlanCode(),
                commercialDiscounts,
                commercialBenefits,
                thirdPartyRuleSettingsService.getAll(),
                contractChangePending,
                item.getPendingContractDiscountPercent(),
                item.getPendingContractRearWindowBranding(),
                new java.util.ArrayList<>(item.pendingContractBenefitCodesSet()),
                item.getPendingContractFipeValue(),
                effectivePendingMonthly,
                item.getContractChangeRequestedAt(),
                contractChangeConfirmationUrl,
                contractChangeWhatsappUrl,
                null,
                item.getConsultant() == null ? null : item.getConsultant().getId(), item.getConsultantName(),
                item.getAssignedAnalyst() == null ? null : item.getAssignedAnalyst().getId(), item.getAssignedAnalystName(),
                item.getAnalysisStage(), item.getRegistrationCompletedAt(), item.getRegistrationCompletedByName(), displayStatus,
                item.getCreatedAt(), item.getUpdatedAt(), item.getExpiresAt(), expiredWithoutFiles, item.getCompletedAt(), item.getAdminNote(),
                item.getSupervisionNote(), item.getSupervisionNoteUpdatedAt(), item.getSupervisionNoteByName(), item.getReviewedAt(),
                item.getReviewedByCollaborator() == null ? null : item.getReviewedByCollaborator().getId(),
                item.getReviewedByName(), item.getReviewedByRole(),
                publicUrl, null, null, quotationPdfUrl, whatsappUrl(whatsapp, message), emailUrl(email, subject, message), associateInspectionUrl,
                consultantInspectionUrl, associateDecisionUrl, item.getDecisionMessageSentAt(), decisionMessagePending,
                availableCount, expiredCount, filesExpireAt, item.getAcceptedAt(), item.getAcceptanceProofHash(),
                item.isAcceptanceUserVerified(),
                item.getAcceptanceWhatsappSentTo() == null || item.getAcceptanceWhatsappSentTo().isBlank()
                        ? normalizeAssociatePhone(item.getWhatsapp()) : item.getAcceptanceWhatsappSentTo(),
                item.getAcceptanceDeviceId(),
                item.getAcceptanceLatitude(), item.getAcceptanceLongitude(), item.getAcceptanceAccuracyMeters(),
                item.getAcceptanceLocationCapturedAt(),
                acceptanceProgress.dossier(), acceptanceProgress.responsabilidade(), acceptanceProgress.cienciaPecas(),
                acceptanceProgress.allAccepted(), acceptanceProgress.expired(), acceptanceProgress.completed(),
                assets
        );
    }

    private String associateInspectionWhatsappUrl(InspectionRequest item, String publicUrl) {
        String phone = normalizeAssociatePhone(item.getWhatsapp());
        if (phone == null || publicUrl == null || publicUrl.isBlank()) return null;
        String firstName = item.getAssociateName() == null || item.getAssociateName().isBlank()
                ? "associado" : item.getAssociateName().trim().split("\\s+")[0];
        boolean hasPreservedFile = item.getAssets().stream()
                .filter(asset -> asset.getAssetType() != br.com.nh.cotacao.entity.InspectionAssetType.REPORT)
                .anyMatch(storageService::isAvailable);
        String message;
        if (hasPreservedFile && item.getCompletedAt() == null) {
            message = "Olá, " + firstName + "! Precisamos refazer apenas alguns arquivos da sua vistoria. "
                    + "Os arquivos já aceitos foram mantidos e não precisam ser enviados novamente. "
                    + "Ao abrir o link, o sistema mostrará somente o que está pendente/rejeitado:\n" + publicUrl;
        } else {
            String action = item.getRequestType() == InspectionRequestType.NEW_INSPECTION
                    ? "realizar a vistoria digital completa"
                    : "gravar o vídeo para atualização do boleto";
            message = "Olá, " + firstName + "! Acesse o link abaixo para " + action
                    + " do seu veículo pela " + TenantIdentity.legalName() + ":\n" + publicUrl;
        }
        return whatsappUrl(phone, message);
    }

    private String consultantInspectionWhatsappUrl(InspectionRequest item, String publicUrl) {
        if (item.getConsultant() == null || publicUrl == null || publicUrl.isBlank()) return null;
        String phone = normalizeAssociatePhone(item.getConsultant().getWhatsapp());
        if (phone == null) return null;
        String consultantFirstName = item.getConsultantName() == null || item.getConsultantName().isBlank()
                ? "consultor" : item.getConsultantName().trim().split("\\s+")[0];
        boolean pending = item.getAnalysisStage() == InspectionAnalysisStage.ANALYST_PENDING
                || item.getStatus() == InspectionRequestStatus.WAITING_FILES;
        String message = pending
                ? "Olá, " + consultantFirstName + "! A vistoria de " + item.getAssociateName()
                    + " possui arquivo(s) reprovado(s) ou pendente(s). Envie este link ao associado para refazer somente o que falta:\n" + publicUrl
                : "Olá, " + consultantFirstName + "! Segue o link da vistoria de " + item.getAssociateName() + ":\n" + publicUrl;
        return whatsappUrl(phone, message);
    }

    private String associateDecisionWhatsappUrl(InspectionRequest item) {
        if (item.getStatus() != InspectionRequestStatus.APPROVED
                && item.getStatus() != InspectionRequestStatus.REJECTED) return null;
        String phone = normalizeAssociatePhone(item.getWhatsapp());
        if (phone == null) return null;
        String firstName = item.getAssociateName() == null || item.getAssociateName().isBlank()
                ? "associado" : item.getAssociateName().trim().split("\\s+")[0];
        String message;
        if (item.getStatus() == InspectionRequestStatus.APPROVED) {
            // O aceite final só entra no fluxo depois de Cadastro realizado e sem
            // revisão comercial aguardando confirmação.
            if (item.getRegistrationCompletedAt() == null || item.hasPendingContractChange()) return null;
            String acceptanceUrl = publicWebUrl + "/retrato/?token=" + item.getPublicToken();
            message = "Olá, " + firstName + "! Sua vistoria foi aprovada pela equipe " + TenantIdentity.legalName() + ". "
                    + "Para concluir o aceite digital do PPV/dossiê aprovado, abra o link abaixo no seu próprio aparelho e confirme com a verificação segura disponível nele (biometria, PIN, senha/padrão de bloqueio):\n"
                    + acceptanceUrl
                    + "\n\nA " + TenantIdentity.displayName() + " não recebe nem armazena sua biometria ou o PIN do aparelho.";
        } else {
            message = "Olá, " + firstName + "! Sua vistoria foi recusada pela equipe " + TenantIdentity.legalName() + "."
                    + (item.getAdminNote() == null || item.getAdminNote().isBlank()
                    ? " Entre em contato com o seu consultor para receber as orientações."
                    : " Motivo/orientação: " + item.getAdminNote());
        }
        return whatsappUrl(phone, message);
    }

    private String normalizeAssociatePhone(String value) {
        if (value == null || value.isBlank()) return null;
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 10 || digits.length() == 11) digits = "55" + digits;
        return digits.matches("^[1-9][0-9]{11,14}$") ? digits : null;
    }

    private String quoteAnalysisSummary(Quotation item) {
        return "status=" + item.getStatus() + "; observação=" + value(item.getAdminNote());
    }

    private String inspectionAnalysisSummary(InspectionRequest item) {
        return "status=" + item.getStatus()
                + "; observação=" + value(item.getAdminNote())
                + "; responsável=" + value(item.getReviewedByName());
    }

    private String whatsappUrl(String phone, String message) {
        if (phone == null || phone.isBlank()) return null;
        String normalized = phone.replaceAll("\\D", "");
        return "https://wa.me/" + normalized + "?text=" + UriUtils.encode(message, StandardCharsets.UTF_8);
    }

    private String emailUrl(String email, String subject, String body) {
        if (email == null || email.isBlank()) return null;
        return "mailto:" + email + "?subject=" + UriUtils.encode(subject, StandardCharsets.UTF_8)
                + "&body=" + UriUtils.encode(body, StandardCharsets.UTF_8);
    }

    private String plateLabel(String plate, boolean zeroKm) {
        return plate == null || plate.isBlank() ? (zeroKm ? "Veículo 0 km — sem placa" : "Sem placa") : plate;
    }

    /**
     * Regras de correção cadastral:
     * - ADMIN pode corrigir os dados do associado/veículo enquanto não houver aceite digital;
     * - SUPERVISION_ANALYSIS também pode corrigir vistorias aprovadas antes do aceite digital;
     * - ANALYST permanece limitado à etapa operacional de cadastro;
     * - FIPE, desconto, mensalidade, benefícios e adicionais usam o endpoint contratual próprio.
     */
    private void assertNotExpiredWithoutPreservedFiles(Quotation quotation, InspectionRequest inspection) {
        if (inspection != null && inspection.hasAnyPreservedFile()) return;
        boolean quoteExpired = quotation != null && quotation.getValidUntil() != null
                && OffsetDateTime.now().isAfter(quotation.getValidUntil());
        boolean inspectionExpired = inspection != null && inspection.isExpired();
        if (quoteExpired || inspectionExpired) {
            throw new IllegalArgumentException("Vistoria/cotação vencida, precisa ser refeita.");
        }
    }

    /**
     * Uma vistoria já aprovada entra na fase de fechamento do dossiê. Para Admin e
     * Supervisão, o aceite digital do associado é a única trava de edição nessa fase;
     * o vencimento comercial não deve impedir a correção final antes da assinatura.
     * Arquivos confirmados permanecem preservados; não há mais retenção física geral de 40 dias.
     */
    private void assertNotExpiredWithoutPreservedFilesUnlessApprovedEditable(
            Quotation quotation, InspectionRequest inspection, PortalRole actorRole
    ) {
        boolean privilegedApprovedEdit = inspection != null
                && inspection.getStatus() == InspectionRequestStatus.APPROVED
                && inspection.getAcceptedAt() == null
                && (actorRole == PortalRole.ADMIN
                    || actorRole == PortalRole.SUPERVISION_ANALYSIS
                    || actorRole == PortalRole.CONSULTANT);
        if (privilegedApprovedEdit) return;
        assertNotExpiredWithoutPreservedFiles(quotation, inspection);
    }

    private void assertContractPricingAccess(UUID id, String username, PortalRole actorRole) {
        if (actorRole == PortalRole.ANALYST) {
            portalUserService.assertAnalysisInspectionAccess(username, actorRole, id);
        } else if (actorRole == PortalRole.SUPERVISION_ANALYSIS) {
            portalUserService.assertSupervisionInspectionAccess(username, actorRole, id);
        } else if (actorRole == PortalRole.CONSULTANT) {
            // O consultor pode corrigir a composição comercial somente das próprias
            // vistorias. O vínculo do login com o consultor continua sendo validado.
            portalUserService.assertInspectionAccess(username, actorRole, id);
        } else if (actorRole != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Este usuário não possui permissão para alterar os valores do contrato.");
        }
    }

    private RearWindowBranding validateDiscountAndBranding(
            Quotation quotation, BigDecimal requestedFipe, int discountPercent, RearWindowBranding requestedBranding
    ) {
        DiscountRule rule = discountRuleService.requireApplicable(
                quotation, discountPercent, requestedFipe, quotation.getManufactureYear(), requestedBranding
        );
        return discountRuleService.normalizedBranding(rule, requestedBranding);
    }

    private String normalizeBenefitCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private Set<String> normalizeRequestedBenefits(
            Quotation quotation, BigDecimal requestedFipe, int discountPercent, List<String> requestedCodes
    ) {
        // A etapa de correção sempre usa o catálogo ATUAL do plano. Dessa forma,
        // serviços adicionais criados depois da cotação original também aparecem
        // e entram no cálculo com o preço que está cadastrado hoje.
        Set<String> allowed = commercialPricingService.benefitCodes(quotation);
        Set<String> selected = (requestedCodes == null ? List.<String>of() : requestedCodes).stream()
                .map(this::normalizeBenefitCode)
                .filter(code -> !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> unknown = new LinkedHashSet<>(selected);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Benefício(s)/serviço(s) adicional(is) inválido(s) para o plano atual: "
                    + String.join(", ", unknown));
        }
        if (selected.contains("FUNERAL") && selected.contains("FUNERAL_FAMILY")) {
            throw new IllegalArgumentException("Escolha apenas uma modalidade de auxílio funeral.");
        }

        // Regras estruturais do plano continuam válidas. Tudo que depende do
        // desconto (remover/incluir benefício e limites específicos) vem do
        // cadastro administrável de descontos.
        if (quotation.usesFipeThirdPartyRule() && allowed.contains("THIRD_PARTY_BASE")) {
            // A cobertura-base por FIPE é obrigatória, mas os adicionais de terceiros
            // continuam independentes e podem ser marcados/desmarcados pelo usuário.
            selected.add("THIRD_PARTY_BASE");
        }
        DiscountRule rule = discountRuleService.requireApplicable(
                quotation, discountPercent, requestedFipe, quotation.getManufactureYear(), RearWindowBranding.ANY_PERFORATED
        );
        selected = discountRuleService.applyBenefitRules(quotation, selected, rule);
        return selected;
    }

    private Set<String> normalizeStoredBenefits(
            Quotation quotation, BigDecimal fipe, int discountPercent, Set<String> storedCodes
    ) {
        Set<String> allowed = commercialPricingService.benefitCodes(quotation);
        Set<String> current = commercialPricingService.filterToCurrentCatalog(quotation, storedCodes);
        LinkedHashSet<String> selected = new LinkedHashSet<>(current);
        if (selected.contains("FUNERAL") && selected.contains("FUNERAL_FAMILY")) {
            selected.remove("FUNERAL_FAMILY");
        }
        if (quotation.usesFipeThirdPartyRule() && allowed.contains("THIRD_PARTY_BASE")) {
            // A cobertura-base por FIPE é obrigatória, mas os adicionais de terceiros
            // continuam independentes e podem ser marcados/desmarcados pelo usuário.
            selected.add("THIRD_PARTY_BASE");
        }
        DiscountRule rule = discountRuleService.findApplicableQuietly(
                quotation, discountPercent, fipe, quotation.getManufactureYear()
        ).orElse(null);
        return discountRuleService.applyBenefitRules(quotation, selected, rule);
    }

    private Set<String> normalizedFinalBenefits(Quotation quotation, BigDecimal fipe, int discountPercent) {
        return normalizeStoredBenefits(quotation, fipe, discountPercent, quotation.finalSelectedCoverageCodes());
    }

    private CommercialBenefitResponse toCommercialBenefit(
            Quotation quotation, CommercialPricingService.BenefitCatalogItem benefit,
            BigDecimal fipe, int discountPercent, DiscountRule discountRule, Set<String> selected
    ) {
        String code = normalizeBenefitCode(benefit.code());
        boolean locked = false;
        String lockReason = null;
        boolean isSelected = selected.contains(code);
        String detail = benefit.detail();

        boolean protectedThirdPartyRule = quotation.usesFipeThirdPartyRule() && "THIRD_PARTY_BASE".equals(code);
        if (protectedThirdPartyRule) {
            locked = true;
            isSelected = true;
            BigDecimal limit = quotation.thirdPartyLimitForFipe(fipe, discountPercent);
            detail = limit != null && limit.compareTo(new BigDecimal("100000.00")) >= 0
                    ? "Cobertura de até R$ 100 mil" : "Cobertura de até R$ 50 mil";
            lockReason = "Regra automática do plano por faixa FIPE e desconto.";
        }

        if (protectedThirdPartyRule) {
            // Regra comercial fixa: nenhuma regra administrativa de desconto
            // pode remover, forçar ou alterar o limite/texto desta cobertura.
        } else if (discountPercent > 0 && Quotation.isDiscountExcludedBenefit(code)) {
            locked = true;
            isSelected = false;
            lockReason = "Não disponível quando há desconto. Regra fixa do produto para Fenômenos da Natureza e Pequenos Reparos.";
        } else {
            var effect = discountRuleService.benefitRule(discountRule, code).orElse(null);
            if (effect != null) {
                if (effect.getAction() == DiscountBenefitAction.REMOVE) {
                    locked = true;
                    isSelected = false;
                    lockReason = "Removido pela regra administrativa do desconto de " + discountRule.getPercent() + "%.";
                } else if (effect.getAction() == DiscountBenefitAction.FORCE_INCLUDE) {
                    locked = true;
                    isSelected = true;
                    lockReason = "Incluído obrigatoriamente pela regra administrativa do desconto de " + discountRule.getPercent() + "%.";
                }
                // KEEP permite alterar somente limite/texto sem travar a seleção do benefício.
                detail = discountRuleService.adjustedBenefitDetail(discountRule, code, detail);
            }
        }

        return new CommercialBenefitResponse(
                benefit.code(), benefit.name(), benefit.status(), detail,
                benefit.monthlyPrice(), isSelected, locked, lockReason
        );
    }

    private String contractRevisionSummary(
            BigDecimal fipe, BigDecimal monthly, Integer discount, RearWindowBranding branding, Set<String> benefits
    ) {
        return "FIPE=" + fipe + "; mensalidade=" + monthly + "; desconto=" + discount
                + "% ; vigia=" + branding + "; benefícios=" + String.join(",", benefits);
    }

    private void assertContractValuesEditable(InspectionRequest inspection, PortalRole actorRole) {
        if (inspection == null) return;
        assertDigitalAcceptanceNotFinalized(inspection);

        // Admin, Supervisão e o consultor responsável podem corrigir a composição
        // comercial enquanto o associado ainda não realizou o aceite digital.
        // O aceite é a trava definitiva do dossiê. Para CONSULTANT o acesso à
        // própria vistoria já foi validado em assertContractPricingAccess().
        if (actorRole == PortalRole.ADMIN
                || actorRole == PortalRole.SUPERVISION_ANALYSIS
                || actorRole == PortalRole.CONSULTANT) return;

        InspectionAnalysisStage stage = inspection.getAnalysisStage();
        if (actorRole == PortalRole.ANALYST
                && stage != InspectionAnalysisStage.ANALYST_QUEUE
                && stage != InspectionAnalysisStage.ANALYST_PENDING) {
            throw new IllegalArgumentException(
                    "O analista pode alterar FIPE, mensalidade e benefícios somente enquanto a vistoria estiver na etapa de análise/cadastro."
            );
        }
        if (stage == InspectionAnalysisStage.FINISHED) {
            throw new IllegalArgumentException("Esta vistoria já está finalizada para o perfil atual.");
        }
    }

    private void assertEditableForActor(InspectionRequest inspection, PortalRole actorRole) {
        if (inspection == null) return;
        assertDigitalAcceptanceNotFinalized(inspection);

        if (actorRole == PortalRole.ADMIN || actorRole == PortalRole.SUPERVISION_ANALYSIS) return;

        // Analistas continuam limitados à etapa operacional de cadastro.
        boolean registrationCompleted = inspection.getAnalysisStage() == InspectionAnalysisStage.SUPERVISION_QUEUE
                || inspection.getAnalysisStage() == InspectionAnalysisStage.FINISHED;

        if (registrationCompleted) {
            throw new IllegalArgumentException(
                    "O analista não pode alterar os dados depois que a vistoria estiver com status Cadastro feito."
            );
        }
    }

    private void assertDigitalAcceptanceNotFinalized(InspectionRequest inspection) {
        if (inspection != null && inspection.getAcceptedAt() != null) {
            throw new IllegalArgumentException(
                    "Esta vistoria já possui aceite digital do associado e está bloqueada para edição."
            );
        }
    }

    private void refreshInspectionDossierAfterEdit(InspectionRequest inspection) {
        if (inspection == null) return;
        if (inspection.getStatus() == InspectionRequestStatus.APPROVED && inspection.getAcceptedAt() == null) {
            inspection.invalidatePendingDigitalAcceptance();
            inspectionRepository.flush();
            persistFinalInspectionDossier(inspection);
            return;
        }
        refreshNonFinalInspectionReportIfPresent(inspection);
    }

    private String normalizeRequiredCpf(String cpf) {
        String digits = cpf == null ? "" : cpf.replaceAll("\\D", "");
        if (digits.length() != 11) {
            throw new IllegalArgumentException("Informe um CPF válido com 11 dígitos.");
        }
        return digits;
    }

    private String normalizeOptionalCpf(String cpf) {
        if (cpf == null || cpf.isBlank()) return null;
        return normalizeRequiredCpf(cpf);
    }

    private String editableDataSummary(String name, String cpf, String whatsapp, String plate, String model, Integer modelYear,
                                       boolean zeroKm, String observation, String residenceAddress) {
        return "nome=" + value(name)
                + "; cpf=" + maskCpf(cpf)
                + "; whatsapp=" + value(whatsapp)
                + "; placa=" + value(plate)
                + "; zeroKm=" + zeroKm
                + "; modelo=" + value(model)
                + "; anoModelo=" + (modelYear == null ? "—" : modelYear)
                + "; observação=" + value(observation)
                + "; endereço=" + value(residenceAddress);
    }

    private String value(String value) { return value == null || value.isBlank() ? "—" : value; }
    private String stripTrailingSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    private String normalizePublicWebUrl(String value) {
        String normalized = stripTrailingSlash(value);
        if (normalized.isBlank() || normalized.matches("(?i)^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?$")) {
            return DEFAULT_PUBLIC_WEB_URL;
        }
        return normalized;
    }
    private String formatCpf(String cpf) {
        if (cpf == null || cpf.isBlank()) return "—";
        String digits = cpf.replaceAll("\\D", "");
        return digits.length() == 11
                ? digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-" + digits.substring(9)
                : cpf;
    }

    private String maskCpf(String cpf) {
        if (cpf == null || cpf.isBlank()) return "***.***.***-**";
        String digits = cpf.replaceAll("\\D", "");
        return digits.length() == 11
                ? "***." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-**"
                : "***.***.***-**";
    }
}
