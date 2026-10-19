package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.ChecklistEventDtos.*;
import br.com.nh.cotacao.entity.CollaboratorRole;
import br.com.nh.cotacao.repository.ConsultantRepository;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChecklistEventService {
    public static final long MAX_UPLOAD_BYTES = 15L * 1024L * 1024L;

    private static final Set<String> EVENT_TYPES = Set.of(
            "COLLISION", "GLASS", "THEFT", "FIRE", "COLLISION_FIRE", "SETTLEMENT_RELEASE"
    );
    private static final Set<String> VEHICLE_CATEGORIES = Set.of("MOTORCYCLE", "LIGHT_CAR", "UTILITY", "TRUCK");
    private static final Set<String> REPORTED_STATES = Set.of("UNANSWERED", "DAMAGED", "NO_DAMAGE", "NOT_APPLICABLE");
    private static final Set<String> ANALYSIS_STATES = Set.of("UNASSESSED", "CLAIM_RELATED", "PREEXISTING", "NOT_DAMAGED", "NOT_APPLICABLE");
    private static final Set<String> TOW_ANSWERS = Set.of("UNANSWERED", "YES", "NO", "NOT_APPLICABLE");
    private static final Set<String> REVIEW_STATUSES = Set.of("WAITING_ANALYSIS", "IN_ANALYSIS", "PENDING", "CHECKLIST_COMPLETED", "FINALIZED");
    private static final Set<String> ATTACHMENT_CONTEXTS = Set.of("EVENT", "DOCUMENT", "PRE_COLLISION", "TOW", "THIRD_PARTY", "WORKSHOP", "OTHER");
    private static final Set<String> ATTACHMENT_KINDS = Set.of(
            "EVENT_PHOTO", "POLICE_REPORT", "PARTICIPATION_PAYMENT", "PRE_COLLISION_INSPECTION",
            "THIRD_PARTY_PHOTO", "SETTLEMENT_RELEASE", "TOW_PHOTO", "WORKSHOP_PHOTO", "WORKSHOP_DOCUMENT", "OTHER"
    );
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "application/pdf"
    );

    public static final Map<String, String> EVENT_TYPE_LABELS = orderedMap(
            "COLLISION", "Colisão",
            "GLASS", "Vidros",
            "THEFT", "Roubo ou furto",
            "FIRE", "Incêndio",
            "COLLISION_FIRE", "Incêndio por colisão",
            "SETTLEMENT_RELEASE", "Termo de acordo/quitação do evento"
    );
    public static final Map<String, String> VEHICLE_CATEGORY_LABELS = orderedMap(
            "MOTORCYCLE", "Motocicleta",
            "LIGHT_CAR", "Carro leve / passeio",
            "UTILITY", "Utilitário / pickup / van",
            "TRUCK", "Caminhão / veículo pesado"
    );
    public static final Map<String, String> STATUS_LABELS = orderedMap(
            "WAITING_DOCUMENTS", "Aguardando documentos",
            "WAITING_WORKSHOP", "Aguardando oficina",
            "IN_WORKSHOP", "Em vistoria da oficina",
            "WORKSHOP_COMPLETED", "Checklist da oficina concluído",
            "WAITING_ANALYSIS", "Aguardando análise",
            "IN_ANALYSIS", "Em análise",
            "PENDING", "Pendência",
            "CHECKLIST_COMPLETED", "Checklist concluído",
            "FINALIZED", "Finalizado"
    );
    public static final Map<String, String> REPORTED_STATE_LABELS = orderedMap(
            "UNANSWERED", "Não informado",
            "DAMAGED", "Com avaria/dano informado",
            "NO_DAMAGE", "Sem avaria aparente",
            "NOT_APPLICABLE", "Não se aplica"
    );
    public static final Map<String, String> ANALYSIS_STATE_LABELS = orderedMap(
            "UNASSESSED", "Não avaliado",
            "CLAIM_RELATED", "Relacionado ao sinistro",
            "PREEXISTING", "Dano preexistente",
            "NOT_DAMAGED", "Sem dano",
            "NOT_APPLICABLE", "Não se aplica"
    );
    public static final Map<String, String> TOW_ANSWER_LABELS = orderedMap(
            "UNANSWERED", "Não informado",
            "YES", "Sim",
            "NO", "Não",
            "NOT_APPLICABLE", "Não se aplica"
    );
    public static final Map<String, String> ATTACHMENT_KIND_LABELS = orderedMap(
            "EVENT_PHOTO", "Fotos do evento",
            "POLICE_REPORT", "Boletim de ocorrência",
            "PARTICIPATION_PAYMENT", "Comprovante da taxa de participação",
            "PRE_COLLISION_INSPECTION", "Vistoria anterior",
            "THIRD_PARTY_PHOTO", "Fotos do terceiro",
            "SETTLEMENT_RELEASE", "Termo de acordo/quitação",
            "TOW_PHOTO", "Fotos do guincho/reboque",
            "WORKSHOP_PHOTO", "Fotos da oficina",
            "WORKSHOP_DOCUMENT", "Documentos da oficina",
            "OTHER", "Outro documento/arquivo"
    );

    private final JdbcTemplate jdbc;
    private final PortalUserService portalUserService;
    private final ConsultantRepository consultantRepository;

    public ChecklistEventService(
            JdbcTemplate jdbc,
            PortalUserService portalUserService,
            ConsultantRepository consultantRepository
    ) {
        this.jdbc = jdbc;
        this.portalUserService = portalUserService;
        this.consultantRepository = consultantRepository;
    }

    @Transactional(readOnly = true)
    public MetaResponse meta() {
        Map<String, List<TemplateItem>> templates = new LinkedHashMap<>();
        for (String category : VEHICLE_CATEGORY_LABELS.keySet()) {
            templates.put(category, checklistTemplate(category));
        }
        List<TowTemplateItem> towTemplate = jdbc.query(
                """
                select id, section, label, sort_order
                  from nh_tow_checklist_template_items
                 where active = true
                 order by sort_order, label
                """,
                (rs, rowNum) -> new TowTemplateItem(
                        uuid(rs, "id"), rs.getString("section"), rs.getString("label"), rs.getInt("sort_order")
                )
        );
        Map<String, List<DocumentRequirement>> requirements = new LinkedHashMap<>();
        for (String type : EVENT_TYPE_LABELS.keySet()) requirements.put(type, documentRequirements(type));
        return new MetaResponse(
                EVENT_TYPE_LABELS, VEHICLE_CATEGORY_LABELS, STATUS_LABELS,
                REPORTED_STATE_LABELS, ANALYSIS_STATE_LABELS, TOW_ANSWER_LABELS,
                ATTACHMENT_KIND_LABELS, templates, towTemplate, requirements, MAX_UPLOAD_BYTES
        );
    }

    @Transactional(readOnly = true)
    public List<EventSummary> list(PortalPrincipal principal, String query, String status) {
        Actor actor = actor(principal, null);
        StringBuilder sql = new StringBuilder("""
                select id, protocol, event_type, status, associate_name, associate_number, plan_name,
                       vehicle_plate, vehicle_model, vehicle_category, tow_service, has_third_party,
                       created_by_name, occurred_at, created_at, updated_at, created_by_username,
                       created_by_collaborator_id
                  from nh_event_records
                 where 1=1
                """);
        List<Object> args = new ArrayList<>();
        appendAccessFilter(sql, args, actor);
        if (status != null && !status.isBlank()) {
            if (!STATUS_LABELS.containsKey(status)) throw new IllegalArgumentException("Status de evento inválido.");
            sql.append(" and status = ?");
            args.add(status);
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" and (lower(protocol) like ? or lower(associate_name) like ? or lower(associate_number) like ? or lower(vehicle_plate) like ? or lower(vehicle_model) like ?)");
            for (int i = 0; i < 5; i++) args.add(pattern);
        }
        sql.append(" order by created_at desc limit 500");
        return jdbc.query(sql.toString(), this::mapSummary, args.toArray());
    }

    @Transactional
    public EventDetail create(CreateEventRequest request, PortalPrincipal principal) {
        validateEventType(request.eventType());
        validateVehicleCategory(request.vehicleCategory());
        Actor actor = actor(principal, request.responsibleConsultantId());
        assertRegistrationEditor(actor);
        validateNonNegative(request.planMonthlyAmount(), "Mensalidade do plano");
        validateNonNegative(request.participationAmount(), "Taxa de participação");
        validateNonNegative(request.fipeValue(), "Valor FIPE");
        validateNonNegative(request.vehicleOdometer(), "Quilometragem");

        String eventType = request.eventType();
        String category = request.vehicleCategory();
        boolean thirdAllowed = eventType.equals("COLLISION") || eventType.equals("COLLISION_FIRE");
        if (request.hasThirdParty() && !thirdAllowed) {
            throw new IllegalArgumentException("Terceiros só podem ser informados em colisão ou incêndio por colisão.");
        }
        List<ThirdPartyCreateRequest> thirdParties = request.thirdParties() == null ? List.of() : request.thirdParties();
        if (request.hasThirdParty() && thirdParties.isEmpty()) {
            throw new IllegalArgumentException("Informe pelo menos um terceiro envolvido.");
        }
        if (!request.hasThirdParty() && !thirdParties.isEmpty()) {
            throw new IllegalArgumentException("Marque que houve terceiro envolvido antes de cadastrá-lo.");
        }

        String normalizedPlate = normalizePlate(request.vehiclePlate());
        TowLookupResponse tow = towByPlateInternal(normalizedPlate);
        UUID eventId = UUID.randomUUID();
        String protocol = nextProtocol();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("""
                insert into nh_event_records(
                    id, protocol, event_type, status, occurred_at, location, description,
                    associate_name, associate_number, plan_name, plan_code, plan_monthly_amount, participation_amount, coverage_notes,
                    vehicle_plate, vehicle_brand, vehicle_model, vehicle_version, vehicle_category, vehicle_subtype,
                    vehicle_year, vehicle_color, vehicle_fuel, vehicle_transmission, vehicle_odometer, fipe_code, fipe_value, chassis,
                    has_third_party, tow_service, linked_tow_record_id,
                    created_by_username, created_by_name, created_by_collaborator_id, last_updated_by, created_at, updated_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                eventId, protocol, eventType, "WAITING_DOCUMENTS", request.occurredAt(), clean(request.location(), 260),
                clean(request.description(), 8000), required(request.associateName(), "Nome do associado", 180),
                required(request.associateNumber(), "Número do associado", 80), required(request.planName(), "Plano", 140),
                clean(request.planCode(),80),request.planMonthlyAmount(),request.participationAmount(),clean(request.coverageNotes(),5000),
                normalizedPlate,required(request.vehicleBrand(), "Marca do veículo",120),required(request.vehicleModel(), "Modelo do veículo", 180),
                clean(request.vehicleVersion(),180),category,clean(request.vehicleSubtype(),80),clean(request.vehicleYear(),20),clean(request.vehicleColor(),80),
                clean(request.vehicleFuel(),60),clean(request.vehicleTransmission(),60),request.vehicleOdometer(),clean(request.fipeCode(),40),request.fipeValue(),clean(request.chassis(),80),
                request.hasThirdParty(), false, tow == null ? null : tow.id(), actor.username(), actor.name(), actor.collaboratorId(), actor.name(), now, now
        );

        // O colaborador não preenche checklist. As instâncias ficam em branco para a oficina avaliar posteriormente.
        insertChecklist(eventId, null, category, List.of(), actor.name(), false);
        for (ThirdPartyCreateRequest third : thirdParties) insertThirdParty(eventId, third, actor.name(), false);
        audit(eventId, actor, "EVENT_CREATED", "EVENT", eventId,
                "Evento " + protocol + " cadastrado sem checklist do colaborador." + (tow != null ? " Reboque " + tow.code() + " vinculado automaticamente pela placa." : ""));
        return detail(eventId, principal);
    }

    @Transactional
    public void deleteEvent(UUID eventId, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        if (actor.role() != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Somente o administrador pode excluir eventos.");
        }
        EventRow event = requireEvent(eventId, actor);
        int removed = jdbc.update("delete from nh_event_records where id = ?", eventId);
        if (removed != 1) throw new IllegalArgumentException("Evento não encontrado.");
    }

    @Transactional(readOnly = true)
    public EventDetail detail(UUID eventId, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        EventRow event = requireEvent(eventId, actor);
        List<ChecklistItemResponse> checklist = checklistItems(eventId, null);
        List<TowItemResponse> tow = towItems(eventId);
        List<ThirdPartyResponse> thirds = thirdParties(eventId);
        List<AttachmentResponse> attachments = attachments(eventId);
        List<DocumentRequirement> requirements = documentRequirements(event.eventType());
        List<AuditLogResponse> audit = audit(eventId);
        boolean reviewer = canAnalyze(actor.role());
        boolean finalized = "FINALIZED".equals(event.status());
        return new EventDetail(
                event.id(), event.protocol(), event.eventType(), event.status(), event.occurredAt(), event.location(), event.description(),
                event.associateName(), event.associateNumber(), event.planName(), event.planCode(), event.planMonthlyAmount(), event.participationAmount(), event.coverageNotes(),
                event.vehiclePlate(), event.vehicleBrand(), event.vehicleModel(), event.vehicleVersion(), event.vehicleCategory(), event.vehicleSubtype(),
                event.vehicleYear(), event.vehicleColor(), event.vehicleFuel(), event.vehicleTransmission(), event.vehicleOdometer(), event.fipeCode(), event.fipeValue(), event.chassis(),
                event.hasThirdParty(), event.towService(), event.pendingReason(),
                event.managerNotes(), event.createdByUsername(), event.createdByName(), event.createdByCollaboratorId(), event.lastUpdatedBy(),
                event.createdAt(), event.updatedAt(), event.registrationCompletedAt(), event.analysisCompletedAt(), event.finalizedAt(),
                checklist, tow, thirds, attachments, requirements, audit,
                !finalized && canEditRegistration(actor.role()), reviewer && !finalized, reviewer && "CHECKLIST_COMPLETED".equals(event.status())
        );
    }

    /**
     * Leitura interna usada para geração/aceite do dossiê público. Não expõe um endpoint autenticado por si só;
     * o chamador deve resolver o evento por token público seguro.
     */
    @Transactional(readOnly = true)
    public EventDetail systemDetail(UUID eventId) {
        EventRow event = jdbc.query("""
                select id,protocol,event_type,status,occurred_at,location,description,associate_name,associate_number,
                       plan_name,plan_code,plan_monthly_amount,participation_amount,coverage_notes,
                       vehicle_plate,vehicle_brand,vehicle_model,vehicle_version,vehicle_category,vehicle_subtype,vehicle_year,vehicle_color,
                       vehicle_fuel,vehicle_transmission,vehicle_odometer,fipe_code,fipe_value,chassis,has_third_party,tow_service,
                       pending_reason,manager_notes,created_by_username,created_by_name,created_by_collaborator_id,last_updated_by,
                       created_at,updated_at,registration_completed_at,analysis_completed_at,finalized_at
                  from nh_event_records where id = ?
                """, rs -> rs.next() ? mapEventRow(rs) : null, eventId);
        if (event == null) throw new IllegalArgumentException("Evento não encontrado.");
        List<ChecklistItemResponse> checklist = checklistItems(eventId, null);
        List<TowItemResponse> tow = towItems(eventId);
        List<ThirdPartyResponse> thirds = thirdParties(eventId);
        List<AttachmentResponse> attachments = attachments(eventId);
        List<DocumentRequirement> requirements = documentRequirements(event.eventType());
        List<AuditLogResponse> audit = audit(eventId);
        return new EventDetail(
                event.id(), event.protocol(), event.eventType(), event.status(), event.occurredAt(), event.location(), event.description(),
                event.associateName(), event.associateNumber(), event.planName(), event.planCode(), event.planMonthlyAmount(), event.participationAmount(), event.coverageNotes(),
                event.vehiclePlate(), event.vehicleBrand(), event.vehicleModel(), event.vehicleVersion(), event.vehicleCategory(), event.vehicleSubtype(),
                event.vehicleYear(), event.vehicleColor(), event.vehicleFuel(), event.vehicleTransmission(), event.vehicleOdometer(), event.fipeCode(), event.fipeValue(), event.chassis(),
                event.hasThirdParty(), event.towService(), event.pendingReason(), event.managerNotes(), event.createdByUsername(), event.createdByName(),
                event.createdByCollaboratorId(), event.lastUpdatedBy(), event.createdAt(), event.updatedAt(), event.registrationCompletedAt(),
                event.analysisCompletedAt(), event.finalizedAt(), checklist, tow, thirds, attachments, requirements, audit, false, false, false
        );
    }

    @Transactional(readOnly = true)
    public List<AttachmentContent> systemImageAttachments(UUID eventId) {
        return jdbc.query("""
                select original_name, content_type, file_data
                  from nh_event_attachments
                 where event_id = ? and content_type in ('image/jpeg','image/png','image/webp')
                 order by created_at
                 limit 80
                """, (rs, rowNum) -> new AttachmentContent(rs.getString(1), rs.getString(2), rs.getBytes(3)), eventId);
    }

    @Transactional
    public EventDetail update(UUID eventId, UpdateEventRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        assertRegistrationEditor(actor);
        EventRow current = requireEvent(eventId, actor);
        assertNotFinalized(current);
        if (request.vehicleCategory() != null) {
            validateVehicleCategory(request.vehicleCategory());
            if (current.registrationCompletedAt() != null && !request.vehicleCategory().equals(current.vehicleCategory())) {
                throw new IllegalArgumentException("A categoria do veículo não pode ser alterada depois que o evento foi enviado para a oficina.");
            }
        }
        validateNonNegative(request.planMonthlyAmount(), "Mensalidade do plano");
        validateNonNegative(request.participationAmount(), "Taxa de participação");
        validateNonNegative(request.fipeValue(), "Valor FIPE");
        validateNonNegative(request.vehicleOdometer(), "Quilometragem");
        jdbc.update("""
                update nh_event_records
                   set occurred_at = coalesce(?, occurred_at),
                       location = coalesce(?, location),
                       description = coalesce(?, description),
                       associate_name = coalesce(?, associate_name),
                       associate_number = coalesce(?, associate_number),
                       plan_name = coalesce(?, plan_name),
                       plan_code = coalesce(?, plan_code),
                       plan_monthly_amount = coalesce(?, plan_monthly_amount),
                       participation_amount = coalesce(?, participation_amount),
                       coverage_notes = coalesce(?, coverage_notes),
                       vehicle_plate = coalesce(?, vehicle_plate),
                       vehicle_brand = coalesce(?, vehicle_brand),
                       vehicle_model = coalesce(?, vehicle_model),
                       vehicle_version = coalesce(?, vehicle_version),
                       vehicle_category = coalesce(?, vehicle_category),
                       vehicle_subtype = coalesce(?, vehicle_subtype),
                       vehicle_year = coalesce(?, vehicle_year),
                       vehicle_color = coalesce(?, vehicle_color),
                       vehicle_fuel = coalesce(?, vehicle_fuel),
                       vehicle_transmission = coalesce(?, vehicle_transmission),
                       vehicle_odometer = coalesce(?, vehicle_odometer),
                       fipe_code = coalesce(?, fipe_code),
                       fipe_value = coalesce(?, fipe_value),
                       chassis = coalesce(?, chassis),
                       pending_reason = coalesce(?, pending_reason),
                       manager_notes = coalesce(?, manager_notes),
                       last_updated_by = ?, updated_at = now()
                 where id = ?
                """,
                request.occurredAt(), nullableClean(request.location(), 260), nullableClean(request.description(), 8000),
                nullableRequired(request.associateName(), 180), nullableRequired(request.associateNumber(), 80), nullableRequired(request.planName(), 140),
                nullableClean(request.planCode(),80),request.planMonthlyAmount(),request.participationAmount(),nullableClean(request.coverageNotes(),5000),
                request.vehiclePlate() == null ? null : normalizePlate(request.vehiclePlate()), nullableRequired(request.vehicleBrand(),120), nullableRequired(request.vehicleModel(), 180),
                nullableClean(request.vehicleVersion(),180), request.vehicleCategory(), nullableClean(request.vehicleSubtype(),80),
                nullableClean(request.vehicleYear(), 20), nullableClean(request.vehicleColor(), 80), nullableClean(request.vehicleFuel(),60),nullableClean(request.vehicleTransmission(),60),
                request.vehicleOdometer(),nullableClean(request.fipeCode(),40),request.fipeValue(),nullableClean(request.chassis(), 80),
                nullableClean(request.pendingReason(), 5000), nullableClean(request.managerNotes(), 5000), actor.name(), eventId
        );
        if (request.vehicleCategory() != null && !request.vehicleCategory().equals(current.vehicleCategory())) {
            jdbc.update("delete from nh_event_checklist_items where event_id=? and third_party_id is null", eventId);
            insertChecklist(eventId, null, request.vehicleCategory(), List.of(), actor.name(), false);
        }
        audit(eventId, actor, "EVENT_UPDATED", "EVENT", eventId, "Dados do evento atualizados.");
        return detail(eventId, principal);
    }

    @Transactional
    public EventDetail updateChecklistItem(UUID eventId, UUID itemId, UpdateChecklistItemRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        Map<String, Object> item = jdbc.queryForList(
                "select id, reported_state, analysis_state from nh_event_checklist_items where id = ? and event_id = ?",
                itemId, eventId
        ).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Item do checklist não encontrado."));

        String reported = request.reportedState();
        String analysis = request.analysisState();
        if (reported != null) {
            if (actor.role() != PortalRole.ADMIN) {
                throw new IllegalArgumentException("O colaborador não preenche checklist. O checklist técnico é exclusivo da oficina.");
            }
            validateEnum(reported, REPORTED_STATES, "Situação informada");
            jdbc.update("""
                    update nh_event_checklist_items
                       set reported_state = ?, reported_notes = ?, updated_by = ?, updated_at = now()
                     where id = ? and event_id = ?
                    """, reported, clean(request.reportedNotes(), 3000), actor.name(), itemId, eventId);
            audit(eventId, actor, "CHECKLIST_REPORTED_UPDATED", "CHECKLIST_ITEM", itemId,
                    "Situação informada alterada de " + item.get("reported_state") + " para " + reported + ".");
        }
        if (analysis != null) {
            assertAnalyzer(actor);
            validateEnum(analysis, ANALYSIS_STATES, "Classificação da análise");
            jdbc.update("""
                    update nh_event_checklist_items
                       set analysis_state = ?, analysis_notes = ?, updated_by = ?, updated_at = now()
                     where id = ? and event_id = ?
                    """, analysis, clean(request.analysisNotes(), 3000), actor.name(), itemId, eventId);
            jdbc.update("update nh_event_records set status = case when status in ('WAITING_ANALYSIS','PENDING') then 'IN_ANALYSIS' else status end, last_updated_by = ?, updated_at = now() where id = ?",
                    actor.name(), eventId);
            audit(eventId, actor, "CHECKLIST_ANALYSIS_UPDATED", "CHECKLIST_ITEM", itemId,
                    "Análise alterada de " + item.get("analysis_state") + " para " + analysis + ".");
        }
        if (reported == null && analysis == null) throw new IllegalArgumentException("Informe uma alteração para o checklist.");
        return detail(eventId, principal);
    }

    @Transactional
    public EventDetail updateTowItem(UUID eventId, UUID itemId, UpdateTowItemRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        if (actor.role() != PortalRole.ADMIN) {
            throw new IllegalArgumentException("O checklist do guincho/reboque deve ser preenchido exclusivamente na Área do Guincho.");
        }
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        validateEnum(request.answer(), TOW_ANSWERS, "Resposta do checklist de guincho");
        if ("UNANSWERED".equals(request.answer())) {
            throw new IllegalArgumentException("Marque Sim, Não ou Não se aplica no checklist de guincho.");
        }
        int updated = jdbc.update("""
                update nh_event_tow_items
                   set answer = ?, notes = ?, updated_by = ?, updated_at = now()
                 where id = ? and event_id = ?
                """, request.answer(), clean(request.notes(), 3000), actor.name(), itemId, eventId);
        if (updated != 1) throw new IllegalArgumentException("Item do checklist de guincho não encontrado.");
        jdbc.update("update nh_event_records set last_updated_by = ?, updated_at = now() where id = ?", actor.name(), eventId);
        audit(eventId, actor, "TOW_CHECKLIST_UPDATED", "TOW_ITEM", itemId, "Checklist de guincho atualizado: " + request.answer() + ".");
        return detail(eventId, principal);
    }

    @Transactional
    public EventDetail addThirdParty(UUID eventId, ThirdPartyCreateRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        assertRegistrationEditor(actor);
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        if (!(event.eventType().equals("COLLISION") || event.eventType().equals("COLLISION_FIRE"))) {
            throw new IllegalArgumentException("Este tipo de evento não permite terceiro envolvido.");
        }
        insertThirdParty(eventId, request, actor.name(), true);
        jdbc.update("update nh_event_records set has_third_party = true, last_updated_by = ?, updated_at = now() where id = ?", actor.name(), eventId);
        audit(eventId, actor, "THIRD_PARTY_CREATED", "THIRD_PARTY", null, "Terceiro envolvido cadastrado com checklist.");
        return detail(eventId, principal);
    }

    @Transactional
    public EventDetail deleteThirdParty(UUID eventId, UUID thirdPartyId, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        assertRegistrationEditor(actor);
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        int removed = jdbc.update("delete from nh_event_third_parties where id = ? and event_id = ?", thirdPartyId, eventId);
        if (removed != 1) throw new IllegalArgumentException("Terceiro não encontrado.");
        Integer count = jdbc.queryForObject("select count(*) from nh_event_third_parties where event_id = ?", Integer.class, eventId);
        if (count != null && count == 0) jdbc.update("update nh_event_records set has_third_party = false, updated_at = now(), last_updated_by = ? where id = ?", actor.name(), eventId);
        audit(eventId, actor, "THIRD_PARTY_DELETED", "THIRD_PARTY", thirdPartyId, "Terceiro removido do evento.");
        return detail(eventId, principal);
    }

    @Transactional
    public EventDetail upload(
            UUID eventId,
            UUID thirdPartyId,
            UUID checklistItemId,
            String context,
            String kind,
            String notes,
            List<MultipartFile> files,
            PortalPrincipal principal
    ) {
        Actor actor = actor(principal, null);
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        validateEnum(context, ATTACHMENT_CONTEXTS, "Contexto do arquivo");
        validateEnum(kind, ATTACHMENT_KINDS, "Tipo do arquivo");
        if ("TOW_PHOTO".equals(kind) && actor.role() != PortalRole.ADMIN) {
            throw new IllegalArgumentException("As fotos do guincho/reboque devem ser enviadas exclusivamente pela Área do Guincho.");
        }
        if (("WORKSHOP_PHOTO".equals(kind) || "WORKSHOP_DOCUMENT".equals(kind))
                && actor.role() != PortalRole.WORKSHOP_MANAGER && actor.role() != PortalRole.SUPERVISION_ANALYSIS && actor.role() != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Fotos e documentos da oficina devem ser enviados pela Área da Oficina.");
        }
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Selecione pelo menos um arquivo.");
        if (thirdPartyId != null) {
            Integer count = jdbc.queryForObject("select count(*) from nh_event_third_parties where id = ? and event_id = ?", Integer.class, thirdPartyId, eventId);
            if (count == null || count == 0) throw new IllegalArgumentException("Terceiro informado não pertence a este evento.");
        }
        if (checklistItemId != null) {
            if (!"WORKSHOP".equals(context) || !"WORKSHOP_PHOTO".equals(kind)) {
                throw new IllegalArgumentException("Fotos vinculadas a um item do checklist só podem ser enviadas como Foto da Oficina.");
            }
            Integer count = jdbc.queryForObject(
                    "select count(*) from nh_event_checklist_items where id = ? and event_id = ?",
                    Integer.class, checklistItemId, eventId);
            if (count == null || count == 0) {
                throw new IllegalArgumentException("Item do checklist da oficina não pertence a este evento.");
            }
        }
        int accepted = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Cada arquivo deve possuir no máximo 15 MB.");
            String contentType = normalizeContentType(file.getContentType(), file.getOriginalFilename());
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new IllegalArgumentException("Envie imagens JPG/PNG/WEBP ou documentos PDF.");
            }
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException exception) {
                throw new IllegalStateException("Não foi possível ler um dos arquivos enviados.");
            }
            if (bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Arquivo vazio ou acima do limite de 15 MB.");
            String originalName = safeFileName(file.getOriginalFilename());
            UUID attachmentId = UUID.randomUUID();
            jdbc.update("""
                    insert into nh_event_attachments(
                        id,event_id,third_party_id,checklist_item_id,context,attachment_kind,original_name,content_type,file_size,file_data,notes,uploaded_by,created_at
                    ) values (?,?,?,?,?,?,?,?,?,?,?,?,now())
                    """, attachmentId, eventId, thirdPartyId, checklistItemId, context, kind, originalName, contentType, (long) bytes.length, bytes,
                    clean(notes, 1000), actor.name());
            audit(eventId, actor, "ATTACHMENT_UPLOADED", "ATTACHMENT", attachmentId,
                    ATTACHMENT_KIND_LABELS.getOrDefault(kind, kind) + ": " + originalName);
            accepted++;
        }
        if (accepted == 0) throw new IllegalArgumentException("Nenhum arquivo válido foi recebido.");
        jdbc.update("update nh_event_records set last_updated_by = ?, updated_at = now() where id = ?", actor.name(), eventId);
        return detail(eventId, principal);
    }

    @Transactional
    public EventDetail deleteAttachment(UUID eventId, UUID attachmentId, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        String attachmentKind = jdbc.query(
                "select attachment_kind from nh_event_attachments where id = ? and event_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, attachmentId, eventId
        );
        if ("TOW_PHOTO".equals(attachmentKind) && actor.role() != PortalRole.ADMIN) {
            throw new IllegalArgumentException("As fotos do guincho/reboque só podem ser removidas pela Área do Guincho.");
        }
        if (("WORKSHOP_PHOTO".equals(attachmentKind) || "WORKSHOP_DOCUMENT".equals(attachmentKind))
                && actor.role() != PortalRole.WORKSHOP_MANAGER && actor.role() != PortalRole.SUPERVISION_ANALYSIS && actor.role() != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Fotos e documentos da oficina só podem ser removidos pela Área da Oficina.");
        }
        int removed = jdbc.update("delete from nh_event_attachments where id = ? and event_id = ?", attachmentId, eventId);
        if (removed != 1) throw new IllegalArgumentException("Arquivo não encontrado.");
        audit(eventId, actor, "ATTACHMENT_DELETED", "ATTACHMENT", attachmentId, "Arquivo removido do evento.");
        return detail(eventId, principal);
    }

    @Transactional(readOnly = true)
    public AttachmentContent attachment(UUID eventId, UUID attachmentId, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        requireEvent(eventId, actor);
        return jdbc.query("""
                select original_name, content_type, file_data
                  from nh_event_attachments
                 where id = ? and event_id = ?
                """, rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Arquivo não encontrado.");
            return new AttachmentContent(rs.getString("original_name"), rs.getString("content_type"), rs.getBytes("file_data"));
        }, attachmentId, eventId);
    }

    @Transactional(readOnly = true)
    public TowLookupResponse towByPlate(String plate, PortalPrincipal principal) {
        actor(principal, null); // valida que a sessão continua ativa
        return towByPlateInternal(normalizePlate(plate));
    }

    private TowLookupResponse towByPlateInternal(String normalizedPlate) {
        return jdbc.query("""
                select id,code,vehicle_plate,vehicle_model,vehicle_category,provider_name,driver_name,general_notes,completed_at,
                       (select count(*) from nh_tow_record_photos p where p.tow_record_id=r.id) photo_count
                  from nh_tow_records r
                 where vehicle_plate=? and status='COMPLETED'
                 order by completed_at desc nulls last,created_at desc
                 limit 1
                """, rs -> {
            if (!rs.next()) return null;
            UUID id = uuid(rs,"id");
            List<TowLookupItem> checklist = jdbc.query("""
                    select label,answer,notes from nh_tow_record_items
                     where tow_record_id=? and answer<>'UNANSWERED' order by sort_order,label
                    """, (itemRs,rowNum) -> new TowLookupItem(itemRs.getString("label"),itemRs.getString("answer"),itemRs.getString("notes")), id);
            return new TowLookupResponse(id,rs.getString("code"),rs.getString("vehicle_plate"),rs.getString("vehicle_model"),
                    rs.getString("vehicle_category"),rs.getString("provider_name"),rs.getString("driver_name"),rs.getString("general_notes"),
                    offset(rs,"completed_at"),rs.getInt("photo_count"),checklist);
        }, normalizedPlate);
    }

    @Transactional(readOnly = true)
    public List<AttachmentContent> imageAttachments(UUID eventId, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        requireEvent(eventId, actor);
        return jdbc.query("""
                select original_name, content_type, file_data
                  from nh_event_attachments
                 where event_id = ? and content_type in ('image/jpeg','image/png','image/webp')
                 order by created_at
                 limit 80
                """, (rs, rowNum) -> new AttachmentContent(rs.getString(1), rs.getString(2), rs.getBytes(3)), eventId);
    }

    @Transactional
    public EventDetail completeRegistration(UUID eventId, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        assertRegistrationEditor(actor);
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        validateRegistrationCompleteness(event);
        jdbc.update("""
                update nh_event_records
                   set status = 'WAITING_WORKSHOP', registration_completed_at = coalesce(registration_completed_at, now()),
                       pending_reason = null, last_updated_by = ?, updated_at = now()
                 where id = ?
                """, actor.name(), eventId);
        audit(eventId, actor, "REGISTRATION_COMPLETED", "EVENT", eventId, "Cadastro, fotos e documentos enviados para a oficina. O colaborador não preenche checklist.");
        return detail(eventId, principal);
    }

    @Transactional
    public EventDetail reviewStatus(UUID eventId, ReviewStatusRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal, null);
        assertAnalyzer(actor);
        EventRow event = requireEvent(eventId, actor);
        assertNotFinalized(event);
        validateEnum(request.status(), REVIEW_STATUSES, "Status da análise");
        String status = request.status();
        if ("CHECKLIST_COMPLETED".equals(status)) validateAnalysisCompleteness(eventId);
        if ("FINALIZED".equals(status) && !"CHECKLIST_COMPLETED".equals(event.status())) {
            throw new IllegalArgumentException("Conclua o checklist de análise antes de finalizar o evento.");
        }
        if ("PENDING".equals(status) && (request.pendingReason() == null || request.pendingReason().isBlank())) {
            throw new IllegalArgumentException("Informe o motivo da pendência.");
        }
        jdbc.update("""
                update nh_event_records
                   set status = ?, pending_reason = ?, manager_notes = ?, last_updated_by = ?, updated_at = now(),
                       analysis_completed_at = case when ? = 'CHECKLIST_COMPLETED' then now() else analysis_completed_at end,
                       finalized_at = case when ? = 'FINALIZED' then now() else finalized_at end
                 where id = ?
                """, status, clean(request.pendingReason(), 5000), clean(request.managerNotes(), 5000), actor.name(), status, status, eventId);
        audit(eventId, actor, "EVENT_STATUS_CHANGED", "EVENT", eventId,
                "Status alterado para " + STATUS_LABELS.getOrDefault(status, status) + ".");
        return detail(eventId, principal);
    }

    @Transactional(readOnly = true)
    public List<TemplateItem> checklistTemplate(String category) {
        validateVehicleCategory(category);
        return jdbc.query("""
                select id, vehicle_category, section, label, sort_order
                  from nh_event_checklist_template_items
                 where vehicle_category = ? and active = true
                 order by sort_order, label
                """, (rs, rowNum) -> new TemplateItem(
                uuid(rs, "id"), rs.getString("vehicle_category"), rs.getString("section"), rs.getString("label"), rs.getInt("sort_order")
        ), category);
    }

    @Transactional(readOnly = true)
    public List<DocumentRequirement> documentRequirements(String eventType) {
        return jdbc.query("""
                select event_type, attachment_kind, label, required, sort_order
                  from nh_event_document_requirements
                 where event_type = ?
                 order by sort_order
                """, (rs, rowNum) -> new DocumentRequirement(
                rs.getString("event_type"), rs.getString("attachment_kind"), rs.getString("label"), rs.getBoolean("required"), rs.getInt("sort_order")
        ), eventType);
    }

    private void insertChecklist(
            UUID eventId,
            UUID thirdPartyId,
            String category,
            List<ChecklistAnswerRequest> answers,
            String actorName,
            boolean requireComplete
    ) {
        List<TemplateItem> template = checklistTemplate(category);
        if (template.isEmpty()) throw new IllegalArgumentException("Checklist não configurado para esta categoria de veículo.");
        Map<UUID, ChecklistAnswerRequest> byId = safeList(answers).stream()
                .collect(Collectors.toMap(ChecklistAnswerRequest::templateItemId, Function.identity(), (a, b) -> b));
        Set<UUID> validIds = template.stream().map(TemplateItem::id).collect(Collectors.toSet());
        if (!validIds.containsAll(byId.keySet())) throw new IllegalArgumentException("O checklist contém item que não pertence à categoria selecionada.");
        for (TemplateItem item : template) {
            ChecklistAnswerRequest answer = byId.get(item.id());
            String state = answer == null ? "UNANSWERED" : answer.state();
            validateEnum(state, REPORTED_STATES, "Resposta do checklist");
            if (requireComplete && "UNANSWERED".equals(state)) {
                throw new IllegalArgumentException("Preencha todos os itens do checklist do veículo antes de registrar o evento.");
            }
            jdbc.update("""
                    insert into nh_event_checklist_items(
                        id,event_id,third_party_id,template_item_id,section,label,sort_order,
                        reported_state,reported_notes,analysis_state,updated_by,updated_at
                    ) values (?,?,?,?,?,?,?,?,?,'UNASSESSED',?,now())
                    """, UUID.randomUUID(), eventId, thirdPartyId, item.id(), item.section(), item.label(), item.sortOrder(), state,
                    answer == null ? null : clean(answer.notes(), 3000), actorName);
        }
    }

    private void insertTowChecklist(UUID eventId, List<TowAnswerRequest> answers, String actorName, boolean requireComplete) {
        List<TowTemplateItem> template = jdbc.query("""
                select id, section, label, sort_order from nh_tow_checklist_template_items
                 where active = true order by sort_order, label
                """, (rs, rowNum) -> new TowTemplateItem(uuid(rs, "id"), rs.getString("section"), rs.getString("label"), rs.getInt("sort_order")));
        Map<UUID, TowAnswerRequest> byId = safeList(answers).stream()
                .collect(Collectors.toMap(TowAnswerRequest::templateItemId, Function.identity(), (a, b) -> b));
        Set<UUID> validIds = template.stream().map(TowTemplateItem::id).collect(Collectors.toSet());
        if (!validIds.containsAll(byId.keySet())) throw new IllegalArgumentException("O checklist de guincho contém item inválido.");
        for (TowTemplateItem item : template) {
            TowAnswerRequest answer = byId.get(item.id());
            String value = answer == null ? "UNANSWERED" : answer.answer();
            validateEnum(value, TOW_ANSWERS, "Resposta do checklist de guincho");
            if (requireComplete && "UNANSWERED".equals(value)) {
                throw new IllegalArgumentException("Preencha todos os itens do checklist do guincho/reboque antes de registrar o evento.");
            }
            jdbc.update("""
                    insert into nh_event_tow_items(
                        id,event_id,template_item_id,section,label,sort_order,answer,notes,updated_by,updated_at
                    ) values (?,?,?,?,?,?,?,?,?,now())
                    """, UUID.randomUUID(), eventId, item.id(), item.section(), item.label(), item.sortOrder(), value,
                    answer == null ? null : clean(answer.notes(), 3000), actorName);
        }
    }

    private UUID insertThirdParty(UUID eventId, ThirdPartyCreateRequest request, String actorName, boolean requireChecklist) {
        validateVehicleCategory(request.vehicleCategory());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into nh_event_third_parties(
                    id,event_id,name,document,phone,vehicle_name,vehicle_plate,vehicle_model,vehicle_color,vehicle_category,notes,created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,now(),now())
                """, id, eventId, required(request.name(), "Nome do terceiro", 180), required(request.document(), "CPF do terceiro", 60),
                required(request.phone(), "Número para contato do terceiro", 30), required(request.vehicle(), "Veículo do terceiro", 180),
                normalizePlate(required(request.vehiclePlate(), "Placa do veículo do terceiro", 20)), required(request.vehicleModel(), "Modelo do veículo do terceiro", 180),
                required(request.vehicleColor(), "Cor do veículo do terceiro", 80), request.vehicleCategory(), clean(request.notes(), 3000));
        insertChecklist(eventId, id, request.vehicleCategory(), request.checklist(), actorName, requireChecklist);
        return id;
    }

    private void validateRegistrationCompleteness(EventRow event) {
        if (event.hasThirdParty()) {
            Integer thirds = jdbc.queryForObject("select count(*) from nh_event_third_parties where event_id = ?", Integer.class, event.id());
            if (thirds == null || thirds == 0) throw new IllegalArgumentException("Cadastre pelo menos um terceiro envolvido.");
        }
        Set<String> uploadedKinds = new HashSet<>(jdbc.query(
                "select distinct attachment_kind from nh_event_attachments where event_id = ?",
                (rs, rowNum) -> rs.getString(1), event.id()
        ));
        List<String> missing = documentRequirements(event.eventType()).stream()
                .filter(DocumentRequirement::required)
                .filter(req -> !uploadedKinds.contains(req.attachmentKind()))
                .map(DocumentRequirement::label)
                .toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("Envie os documentos obrigatórios: " + String.join(", ", missing) + ".");
    }

    private void validateAnalysisCompleteness(UUID eventId) {
        Integer unanswered = jdbc.queryForObject("select count(*) from nh_event_checklist_items where event_id = ? and analysis_state = 'UNASSESSED'", Integer.class, eventId);
        if (unanswered != null && unanswered > 0) {
            throw new IllegalArgumentException("A análise ainda possui itens do checklist não avaliados.");
        }
    }

    private List<ChecklistItemResponse> checklistItems(UUID eventId, UUID thirdPartyId) {
        String sql = """
                select id, third_party_id, template_item_id, section, label, sort_order,
                       reported_state, reported_notes, analysis_state, analysis_notes, updated_by, updated_at
                  from nh_event_checklist_items
                 where event_id = ? and %s
                 order by sort_order, label
                """.formatted(thirdPartyId == null ? "third_party_id is null" : "third_party_id = ?");
        Object[] args = thirdPartyId == null ? new Object[]{eventId} : new Object[]{eventId, thirdPartyId};
        return jdbc.query(sql, (rs, rowNum) -> new ChecklistItemResponse(
                uuid(rs, "id"), nullableUuid(rs, "third_party_id"), nullableUuid(rs, "template_item_id"),
                rs.getString("section"), rs.getString("label"), rs.getInt("sort_order"), rs.getString("reported_state"),
                rs.getString("reported_notes"), rs.getString("analysis_state"), rs.getString("analysis_notes"),
                rs.getString("updated_by"), offset(rs, "updated_at")
        ), args);
    }

    private List<TowItemResponse> towItems(UUID eventId) {
        return jdbc.query("""
                select id, template_item_id, section, label, sort_order, answer, notes, updated_by, updated_at
                  from nh_event_tow_items
                 where event_id = ?
                 order by sort_order, label
                """, (rs, rowNum) -> new TowItemResponse(
                uuid(rs, "id"), nullableUuid(rs, "template_item_id"), rs.getString("section"), rs.getString("label"),
                rs.getInt("sort_order"), rs.getString("answer"), rs.getString("notes"), rs.getString("updated_by"), offset(rs, "updated_at")
        ), eventId);
    }

    private List<ThirdPartyResponse> thirdParties(UUID eventId) {
        List<ThirdPartyBase> bases = jdbc.query("""
                select id,name,document,phone,vehicle_name,vehicle_plate,vehicle_model,vehicle_color,vehicle_category,notes,created_at
                  from nh_event_third_parties where event_id = ? order by created_at
                """, (rs, rowNum) -> new ThirdPartyBase(
                uuid(rs, "id"), rs.getString("name"), rs.getString("document"), rs.getString("phone"), rs.getString("vehicle_name"),
                rs.getString("vehicle_plate"), rs.getString("vehicle_model"), rs.getString("vehicle_color"), rs.getString("vehicle_category"),
                rs.getString("notes"), offset(rs, "created_at")
        ), eventId);
        return bases.stream().map(base -> new ThirdPartyResponse(
                base.id(), base.name(), base.document(), base.phone(), base.vehicle(), base.vehiclePlate(), base.vehicleModel(), base.vehicleColor(),
                base.vehicleCategory(), base.notes(), base.createdAt(), checklistItems(eventId, base.id())
        )).toList();
    }

    private List<AttachmentResponse> attachments(UUID eventId) {
        return jdbc.query("""
                select id,third_party_id,checklist_item_id,context,attachment_kind,original_name,content_type,file_size,notes,uploaded_by,created_at
                  from nh_event_attachments where event_id = ? order by created_at desc
                """, (rs, rowNum) -> new AttachmentResponse(
                uuid(rs, "id"), nullableUuid(rs, "third_party_id"), nullableUuid(rs, "checklist_item_id"), rs.getString("context"), rs.getString("attachment_kind"),
                rs.getString("original_name"), rs.getString("content_type"), rs.getLong("file_size"), rs.getString("notes"),
                rs.getString("uploaded_by"), offset(rs, "created_at")
        ), eventId);
    }

    private List<AuditLogResponse> audit(UUID eventId) {
        return jdbc.query("""
                select id,actor_username,actor_name,action,entity_type,entity_id,details,created_at
                  from nh_event_audit_logs where event_id = ? order by created_at desc limit 300
                """, (rs, rowNum) -> new AuditLogResponse(
                uuid(rs, "id"), rs.getString("actor_username"), rs.getString("actor_name"), rs.getString("action"),
                rs.getString("entity_type"), nullableUuid(rs, "entity_id"), rs.getString("details"), offset(rs, "created_at")
        ), eventId);
    }

    private EventRow requireEvent(UUID eventId, Actor actor) {
        EventRow event = jdbc.query("""
                select id,protocol,event_type,status,occurred_at,location,description,associate_name,associate_number,
                       plan_name,plan_code,plan_monthly_amount,participation_amount,coverage_notes,
                       vehicle_plate,vehicle_brand,vehicle_model,vehicle_version,vehicle_category,vehicle_subtype,vehicle_year,vehicle_color,
                       vehicle_fuel,vehicle_transmission,vehicle_odometer,fipe_code,fipe_value,chassis,has_third_party,tow_service,
                       pending_reason,manager_notes,created_by_username,created_by_name,created_by_collaborator_id,last_updated_by,
                       created_at,updated_at,registration_completed_at,analysis_completed_at,finalized_at
                  from nh_event_records where id = ?
                """, rs -> rs.next() ? mapEventRow(rs) : null, eventId);
        if (event == null) throw new IllegalArgumentException("Evento não encontrado.");
        if (!hasAccess(actor, event)) throw new IllegalArgumentException("Este evento pertence a outro colaborador.");
        return event;
    }

    private boolean hasAccess(Actor actor, EventRow event) {
        if (canAnalyze(actor.role()) || actor.role() == PortalRole.WORKSHOP_MANAGER || actor.role() == PortalRole.EVENT_OPERATOR) return true;
        if (actor.role() != PortalRole.CONSULTANT) return false;
        if (actor.collaboratorId() == null) return true; // conta de consultor genérica mantém o fluxo legado
        return actor.collaboratorId().equals(event.createdByCollaboratorId()) || actor.username().equalsIgnoreCase(event.createdByUsername());
    }

    private void appendAccessFilter(StringBuilder sql, List<Object> args, Actor actor) {
        if (canAnalyze(actor.role()) || actor.role() == PortalRole.WORKSHOP_MANAGER || actor.role() == PortalRole.EVENT_OPERATOR) return;
        if (actor.role() != PortalRole.CONSULTANT) {
            sql.append(" and 1=0");
            return;
        }
        if (actor.collaboratorId() != null) {
            sql.append(" and (created_by_collaborator_id = ? or lower(created_by_username) = lower(?))");
            args.add(actor.collaboratorId());
            args.add(actor.username());
        }
    }

    private Actor actor(PortalPrincipal principal, UUID requestedConsultantId) {
        var session = portalUserService.session(principal.username());
        UUID collaboratorId = session.consultantId();
        String name = firstNonBlank(session.consultantName(), session.displayName(), session.username());
        if (principal.role() == PortalRole.CONSULTANT && requestedConsultantId != null) {
            portalUserService.assertConsultantAccess(principal.username(), principal.role(), requestedConsultantId);
            var consultant = consultantRepository.findById(requestedConsultantId)
                    .filter(c -> c.isActive() && c.getRole() == CollaboratorRole.CONSULTANT)
                    .orElseThrow(() -> new IllegalArgumentException("Selecione um consultor ativo para registrar o evento."));
            collaboratorId = consultant.getId();
            name = consultant.getName();
        }
        return new Actor(session.username(), name, collaboratorId, principal.role());
    }

    private static boolean canAnalyze(PortalRole role) {
        return role == PortalRole.ADMIN || role == PortalRole.ANALYST || role == PortalRole.SUPERVISION_ANALYSIS;
    }

    private static boolean canEditRegistration(PortalRole role) {
        return role == PortalRole.EVENT_OPERATOR || role == PortalRole.ADMIN;
    }

    private static void assertRegistrationEditor(Actor actor) {
        if (!canEditRegistration(actor.role())) {
            throw new IllegalArgumentException("Somente a equipe de Eventos ou a administração pode alterar os dados do evento.");
        }
    }

    private static void assertAnalyzer(Actor actor) {
        if (!canAnalyze(actor.role())) throw new IllegalArgumentException("Somente análise, supervisão ou administração pode classificar o sinistro.");
    }

    private static void assertNotFinalized(EventRow event) {
        if ("FINALIZED".equals(event.status())) throw new IllegalArgumentException("Evento finalizado. O histórico permanece somente para consulta.");
    }

    private void audit(UUID eventId, Actor actor, String action, String entityType, UUID entityId, String details) {
        jdbc.update("""
                insert into nh_event_audit_logs(id,event_id,actor_username,actor_name,action,entity_type,entity_id,details,created_at)
                values (?,?,?,?,?,?,?,?,now())
                """, UUID.randomUUID(), eventId, actor.username(), actor.name(), action, entityType, entityId, clean(details, 8000));
    }

    private String nextProtocol() {
        String prefix = "NH-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        for (int attempt = 0; attempt < 20; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
            String protocol = prefix + suffix;
            Integer count = jdbc.queryForObject("select count(*) from nh_event_records where protocol = ?", Integer.class, protocol);
            if (count == null || count == 0) return protocol;
        }
        return prefix + System.nanoTime();
    }

    private EventSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new EventSummary(
                uuid(rs, "id"), rs.getString("protocol"), rs.getString("event_type"), rs.getString("status"),
                rs.getString("associate_name"), rs.getString("associate_number"), rs.getString("plan_name"),
                rs.getString("vehicle_plate"), rs.getString("vehicle_model"), rs.getString("vehicle_category"),
                rs.getBoolean("tow_service"), rs.getBoolean("has_third_party"), rs.getString("created_by_name"),
                offset(rs, "occurred_at"), offset(rs, "created_at"), offset(rs, "updated_at")
        );
    }

    private EventRow mapEventRow(ResultSet rs) throws SQLException {
        return new EventRow(
                uuid(rs, "id"), rs.getString("protocol"), rs.getString("event_type"), rs.getString("status"), offset(rs, "occurred_at"),
                rs.getString("location"), rs.getString("description"), rs.getString("associate_name"), rs.getString("associate_number"),
                rs.getString("plan_name"),rs.getString("plan_code"),rs.getBigDecimal("plan_monthly_amount"),rs.getBigDecimal("participation_amount"),rs.getString("coverage_notes"),
                rs.getString("vehicle_plate"),rs.getString("vehicle_brand"),rs.getString("vehicle_model"),rs.getString("vehicle_version"),rs.getString("vehicle_category"),
                rs.getString("vehicle_subtype"),rs.getString("vehicle_year"),rs.getString("vehicle_color"),rs.getString("vehicle_fuel"),rs.getString("vehicle_transmission"),
                nullableLong(rs,"vehicle_odometer"),rs.getString("fipe_code"),rs.getBigDecimal("fipe_value"),rs.getString("chassis"), rs.getBoolean("has_third_party"),
                rs.getBoolean("tow_service"), rs.getString("pending_reason"), rs.getString("manager_notes"), rs.getString("created_by_username"),
                rs.getString("created_by_name"), nullableUuid(rs, "created_by_collaborator_id"), rs.getString("last_updated_by"),
                offset(rs, "created_at"), offset(rs, "updated_at"), offset(rs, "registration_completed_at"), offset(rs, "analysis_completed_at"), offset(rs, "finalized_at")
        );
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof UUID uuid) return uuid;
        return UUID.fromString(String.valueOf(value));
    }

    private static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid;
        return UUID.fromString(String.valueOf(value));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getLong(column);
    }

    private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private static String normalizeContentType(String contentType, String fileName) {
        String normalized = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_CONTENT_TYPES.contains(normalized)) return normalized;
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return normalized;
    }

    private static String safeFileName(String value) {
        String name = value == null || value.isBlank() ? "arquivo" : value.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\r\\n\\t]", "_");
        return name.substring(0, Math.min(240, name.length()));
    }

    private static String normalizePlate(String value) {
        String plate = required(value, "Placa do veículo", 20).toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        return plate;
    }

    private static String required(String value, String field, int max) {
        String clean = clean(value, max);
        if (clean == null) throw new IllegalArgumentException("Informe " + field.toLowerCase(Locale.ROOT) + ".");
        return clean;
    }

    private static String nullableRequired(String value, int max) {
        if (value == null) return null;
        String clean = clean(value, max);
        if (clean == null) throw new IllegalArgumentException("Um campo obrigatório não pode ficar vazio.");
        return clean;
    }

    private static String nullableClean(String value, int max) {
        if (value == null) return null;
        return clean(value, max);
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        if (clean.isBlank()) return null;
        return clean.substring(0, Math.min(max, clean.length()));
    }

    private static void validateNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) throw new IllegalArgumentException(field + " não pode ser negativo(a).");
    }

    private static void validateNonNegative(Long value, String field) {
        if (value != null && value < 0) throw new IllegalArgumentException(field + " não pode ser negativa.");
    }

    private static void validateEventType(String value) { validateEnum(value, EVENT_TYPES, "Tipo de evento"); }
    private static void validateVehicleCategory(String value) { validateEnum(value, VEHICLE_CATEGORIES, "Categoria do veículo"); }
    private static void validateEnum(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) throw new IllegalArgumentException(field + " inválido(a).");
    }

    private static <T> List<T> safeList(List<T> value) { return value == null ? List.of() : value; }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "Usuário NH";
    }

    private static Map<String, String> orderedMap(String... pairs) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }

    private record Actor(String username, String name, UUID collaboratorId, PortalRole role) {}
    private record ThirdPartyBase(
            UUID id, String name, String document, String phone, String vehicle, String vehiclePlate,
            String vehicleModel, String vehicleColor, String vehicleCategory, String notes, OffsetDateTime createdAt
    ) {}
    private record EventRow(
            UUID id, String protocol, String eventType, String status, OffsetDateTime occurredAt, String location, String description,
            String associateName, String associateNumber, String planName, String planCode, BigDecimal planMonthlyAmount, BigDecimal participationAmount, String coverageNotes,
            String vehiclePlate, String vehicleBrand, String vehicleModel, String vehicleVersion, String vehicleCategory, String vehicleSubtype,
            String vehicleYear, String vehicleColor, String vehicleFuel, String vehicleTransmission, Long vehicleOdometer, String fipeCode, BigDecimal fipeValue, String chassis,
            boolean hasThirdParty, boolean towService, String pendingReason,
            String managerNotes, String createdByUsername, String createdByName, UUID createdByCollaboratorId, String lastUpdatedBy,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime registrationCompletedAt, OffsetDateTime analysisCompletedAt,
            OffsetDateTime finalizedAt
    ) {}
}
