package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.WorkshopPortalDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class WorkshopPortalService {
    private static final Set<String> DAMAGE_STATES = Set.of("YES", "NO", "NOT_APPLICABLE");
    private static final Set<String> REPAIR_ACTIONS = Set.of("NONE", "REPAIR", "REPLACE");
    private static final Set<String> PURCHASE_STATUSES = Set.of("REQUESTED", "ORDERED", "RECEIVED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final PortalUserService portalUserService;

    public WorkshopPortalService(JdbcTemplate jdbc, PortalUserService portalUserService) {
        this.jdbc = jdbc;
        this.portalUserService = portalUserService;
    }

    @Transactional(readOnly = true)
    public List<WorkshopQueueSummary> list(PortalPrincipal principal, String query, String status) {
        Actor actor = actor(principal);
        assertWorkshopAccess(actor);
        StringBuilder sql = new StringBuilder("""
                select e.id,e.protocol,e.event_type,e.status,e.associate_name,e.associate_number,e.vehicle_plate,e.vehicle_model,
                       e.vehicle_category,e.occurred_at,e.created_at,e.workshop_started_at,e.workshop_completed_at,e.accepted_at,e.public_acceptance_token,
                       (select count(*) from nh_event_checklist_items i where i.event_id=e.id and i.damage_state <> 'UNASSESSED'
                            and (i.template_item_id is null or exists (select 1 from nh_event_checklist_template_items t where t.id=i.template_item_id and t.active=true))) assessed_items,
                       (select count(*) from nh_event_checklist_items i where i.event_id=e.id
                            and (i.template_item_id is null or exists (select 1 from nh_event_checklist_template_items t where t.id=i.template_item_id and t.active=true))) total_items,
                       (select count(*) from nh_event_checklist_items i where i.event_id=e.id and i.damage_state='YES'
                            and (i.template_item_id is null or exists (select 1 from nh_event_checklist_template_items t where t.id=i.template_item_id and t.active=true))) damaged_items,
                       (select count(*) from nh_event_checklist_items i where i.event_id=e.id and i.damage_state='YES' and i.repair_action='REPLACE'
                            and (i.template_item_id is null or exists (select 1 from nh_event_checklist_template_items t where t.id=i.template_item_id and t.active=true))) replace_items,
                       (select count(*) from nh_event_purchase_items p where p.event_id=e.id and p.status <> 'CANCELLED') purchase_items
                  from nh_event_records e
                 where e.registration_completed_at is not null
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("WAITING_WORKSHOP", "IN_WORKSHOP", "WORKSHOP_COMPLETED", "FINALIZED", "PENDING").contains(normalized)) {
                throw new IllegalArgumentException("Status da oficina inválido.");
            }
            sql.append(" and e.status = ?");
            args.add(normalized);
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" and (lower(e.protocol) like ? or lower(e.associate_name) like ? or lower(e.associate_number) like ? or lower(e.vehicle_plate) like ? or lower(e.vehicle_model) like ?)");
            for (int i = 0; i < 5; i++) args.add(pattern);
        }
        sql.append(" order by case when e.workshop_completed_at is null then 0 else 1 end, e.created_at desc limit 500");
        return jdbc.query(sql.toString(), this::mapSummary, args.toArray());
    }

    @Transactional
    public WorkshopEventDetail detail(UUID eventId, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertWorkshopAccess(actor);
        EventRow event = requireEvent(eventId);
        ensureWorkshopChecklists(event);
        return buildDetail(eventId, actor);
    }

    @Transactional
    public WorkshopEventDetail updateItem(UUID eventId, UUID itemId, UpdateWorkshopItemRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertWorkshopAccess(actor);
        EventRow event = requireEvent(eventId);
        assertEditable(event);
        if (event.workshopCompletedAt() != null) throw new IllegalArgumentException("O checklist da oficina já foi finalizado e está disponível apenas para consulta.");
        ensureWorkshopChecklists(event);

        String damage = normalize(request.damageState());
        if (!DAMAGE_STATES.contains(damage)) throw new IllegalArgumentException("Marque se o item possui avaria: Sim, Não ou Não se aplica.");
        String action = normalize(request.repairAction());
        if (damage.equals("YES")) {
            if (!Set.of("REPAIR", "REPLACE").contains(action)) {
                throw new IllegalArgumentException("Quando houver avaria, informe se o item será Recuperado/Reparado ou Trocado.");
            }
        } else {
            action = "NONE";
        }

        int updated = jdbc.update("""
                update nh_event_checklist_items
                   set damage_state=?, repair_action=?, workshop_notes=?, workshop_updated_by=?, workshop_updated_at=now(),
                       updated_by=?, updated_at=now()
                 where id=? and event_id=?
                """, damage, action, clean(request.notes(), 3000), actor.name(), actor.name(), itemId, eventId);
        if (updated != 1) throw new IllegalArgumentException("Item do checklist da oficina não encontrado.");

        jdbc.update("""
                update nh_event_records
                   set status = case when status in ('WAITING_WORKSHOP','WAITING_ANALYSIS','PENDING') then 'IN_WORKSHOP' else status end,
                       workshop_started_at=coalesce(workshop_started_at,now()), workshop_started_by=coalesce(workshop_started_by,?),
                       last_updated_by=?, updated_at=now()
                 where id=?
                """, actor.name(), actor.name(), eventId);
        syncPurchaseForItem(eventId, itemId, actor.name());
        audit(eventId, actor, "WORKSHOP_ITEM_UPDATED", "CHECKLIST_ITEM", itemId,
                "Oficina: avaria=" + damage + ", ação=" + action + ".");
        return buildDetail(eventId, actor);
    }

    @Transactional
    public WorkshopEventDetail addCustomItem(UUID eventId, AddCustomWorkshopItemRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertWorkshopAccess(actor);
        EventRow event = requireEvent(eventId);
        assertEditable(event);
        ensureWorkshopChecklists(event);
        if (event.workshopCompletedAt() != null) {
            throw new IllegalArgumentException("O checklist da oficina já foi concluído.");
        }

        String label = clean(request.label(), 180);
        if (label == null || label.length() < 2) {
            throw new IllegalArgumentException("Digite pelo menos 2 caracteres para adicionar uma peça.");
        }
        UUID thirdPartyId = request.thirdPartyId();
        if (thirdPartyId != null) {
            Integer belongs = jdbc.queryForObject(
                    "select count(*) from nh_event_third_parties where id=? and event_id=?",
                    Integer.class, thirdPartyId, eventId);
            if (belongs == null || belongs == 0) {
                throw new IllegalArgumentException("Veículo de terceiro não encontrado neste evento.");
            }
        }

        List<ExistingItem> existing = thirdPartyId == null
                ? jdbc.query("select id,label from nh_event_checklist_items where event_id=? and third_party_id is null",
                    (rs,rowNum) -> new ExistingItem(uuid(rs,"id"), rs.getString("label")), eventId)
                : jdbc.query("select id,label from nh_event_checklist_items where event_id=? and third_party_id=?",
                    (rs,rowNum) -> new ExistingItem(uuid(rs,"id"), rs.getString("label")), eventId, thirdPartyId);
        String canonical = canonicalLabel(label);
        for (ExistingItem item : existing) {
            if (canonical.equals(canonicalLabel(item.label()))) {
                throw new IllegalArgumentException("Essa peça já existe no checklist: " + item.label() + ".");
            }
        }

        Integer maxSort = thirdPartyId == null
                ? jdbc.queryForObject("select coalesce(max(sort_order),8999) from nh_event_checklist_items where event_id=? and third_party_id is null and section='Diversos'", Integer.class, eventId)
                : jdbc.queryForObject("select coalesce(max(sort_order),8999) from nh_event_checklist_items where event_id=? and third_party_id=? and section='Diversos'", Integer.class, eventId, thirdPartyId);
        int sortOrder = Math.max(9000, (maxSort == null ? 8999 : maxSort) + 1);
        UUID itemId = UUID.randomUUID();
        jdbc.update("""
                insert into nh_event_checklist_items(
                    id,event_id,third_party_id,template_item_id,section,label,sort_order,reported_state,analysis_state,
                    damage_state,repair_action,workshop_updated_by,workshop_updated_at,updated_by,updated_at
                ) values (?,?,?,null,'Diversos',?,?,'UNANSWERED','UNASSESSED','UNASSESSED','NONE',?,now(),?,now())
                """, itemId, eventId, thirdPartyId, label, sortOrder, actor.name(), actor.name());

        jdbc.update("""
                update nh_event_records
                   set status=case when status in ('WAITING_WORKSHOP','WAITING_ANALYSIS','PENDING') then 'IN_WORKSHOP' else status end,
                       workshop_started_at=coalesce(workshop_started_at,now()), workshop_started_by=coalesce(workshop_started_by,?),
                       last_updated_by=?,updated_at=now() where id=?
                """, actor.name(), actor.name(), eventId);
        audit(eventId, actor, "WORKSHOP_CUSTOM_ITEM_ADDED", "CHECKLIST_ITEM", itemId,
                "Peça adicionada manualmente em Diversos: " + label + (thirdPartyId == null ? "." : " (veículo de terceiro)."));
        return buildDetail(eventId, actor);
    }

    @Transactional
    public WorkshopEventDetail markPendingNoDamage(UUID eventId, BulkNoDamageRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertWorkshopAccess(actor);
        EventRow event = requireEvent(eventId);
        assertEditable(event);
        ensureWorkshopChecklists(event);
        if (event.workshopCompletedAt() != null) throw new IllegalArgumentException("O checklist da oficina já foi concluído.");

        String section = clean(request == null ? null : request.section(), 100);
        UUID thirdPartyId = request == null ? null : request.thirdPartyId();
        StringBuilder sql = new StringBuilder("""
                update nh_event_checklist_items i
                   set damage_state='NO', repair_action='NONE', workshop_updated_by=?, workshop_updated_at=now(),
                       updated_by=?, updated_at=now()
                 where i.event_id=? and i.damage_state='UNASSESSED'
                   and (i.template_item_id is null or exists (select 1 from nh_event_checklist_template_items t where t.id=i.template_item_id and t.active=true))
                """);
        List<Object> args = new ArrayList<>();
        args.add(actor.name()); args.add(actor.name()); args.add(eventId);
        if (thirdPartyId == null) sql.append(" and i.third_party_id is null");
        else { sql.append(" and i.third_party_id=?"); args.add(thirdPartyId); }
        if (section != null) { sql.append(" and i.section=?"); args.add(section); }
        int changed = jdbc.update(sql.toString(), args.toArray());
        if (changed > 0) {
            jdbc.update("""
                    update nh_event_records
                       set status=case when status in ('WAITING_WORKSHOP','WAITING_ANALYSIS','PENDING') then 'IN_WORKSHOP' else status end,
                           workshop_started_at=coalesce(workshop_started_at,now()), workshop_started_by=coalesce(workshop_started_by,?),
                           last_updated_by=?,updated_at=now() where id=?
                    """, actor.name(),actor.name(),eventId);
        }
        audit(eventId, actor, "WORKSHOP_BULK_NO_DAMAGE", "EVENT", eventId,
                (section == null ? "Pendências do checklist" : "Pendências da seção " + section) + " marcadas como sem avaria: " + changed + " item(ns).");
        return buildDetail(eventId, actor);
    }

    @Transactional
    public WorkshopEventDetail complete(UUID eventId, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertWorkshopAccess(actor);
        EventRow event = requireEvent(eventId);
        assertEditable(event);
        if (event.workshopCompletedAt() != null) return buildDetail(eventId, actor);
        ensureWorkshopChecklists(event);

        Integer missing = jdbc.queryForObject("select count(*) from nh_event_checklist_items where event_id=? and damage_state='UNASSESSED'", Integer.class, eventId);
        if (missing != null && missing > 0) {
            throw new IllegalArgumentException("Avalie todos os itens do checklist antes de finalizar a vistoria da oficina.");
        }
        Integer withoutAction = jdbc.queryForObject("select count(*) from nh_event_checklist_items where event_id=? and damage_state='YES' and repair_action not in ('REPAIR','REPLACE')", Integer.class, eventId);
        if (withoutAction != null && withoutAction > 0) {
            throw new IllegalArgumentException("Todo item com avaria precisa estar marcado como Recuperar/Reparar ou Trocar.");
        }
        syncAllPurchases(eventId, actor.name());
        int completed = jdbc.update("""
                update nh_event_records
                   set status='WORKSHOP_COMPLETED', workshop_started_at=coalesce(workshop_started_at,now()),
                       workshop_started_by=coalesce(workshop_started_by,?), workshop_completed_at=now(), workshop_completed_by=?,
                       analysis_completed_at=now(), last_updated_by=?, updated_at=now()
                 where id=? and workshop_completed_at is null
                """, actor.name(), actor.name(), actor.name(), eventId);
        if (completed != 1) throw new IllegalStateException("Não foi possível registrar a conclusão do checklist da oficina.");
        audit(eventId, actor, "WORKSHOP_CHECKLIST_COMPLETED", "EVENT", eventId,
                "Checklist técnico da oficina concluído. Os itens marcados para troca foram encaminhados ao Financeiro.");
        return buildDetail(eventId, actor);
    }

    @Transactional
    public WorkshopEventDetail updatePurchase(UUID eventId, UUID purchaseId, UpdatePurchaseRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertWorkshopAccess(actor);
        EventRow event = requireEvent(eventId);
        // Compras são informação operacional interna e continuam editáveis mesmo após o aceite/finalização do dossiê do associado.
        if (event.workshopCompletedAt() == null) throw new IllegalArgumentException("Finalize primeiro o checklist da oficina para liberar as compras do evento.");
        String status = request.status() == null || request.status().isBlank() ? "REQUESTED" : normalize(request.status());
        if (!PURCHASE_STATUSES.contains(status)) throw new IllegalArgumentException("Status de compra inválido.");
        BigDecimal amount = request.amount();
        if (amount != null && amount.signum() < 0) throw new IllegalArgumentException("O valor da compra não pode ser negativo.");
        int updated = jdbc.update("""
                update nh_event_purchase_items
                   set supplier=?,amount=?,delivery_deadline=?,status=?,notes=?,updated_by=?,updated_at=now(),
                       details_saved_at=now(),details_saved_by=?
                 where id=? and event_id=?
                """, clean(request.supplier(),180), amount, request.deliveryDeadline(), status, clean(request.notes(),3000), actor.name(), actor.name(), purchaseId, eventId);
        if (updated != 1) throw new IllegalArgumentException("Item de compra não encontrado.");
        audit(eventId, actor, "PURCHASE_UPDATED", "PURCHASE_ITEM", purchaseId, "Dados de fornecedor/valor/prazo da compra atualizados.");
        return buildDetail(eventId, actor);
    }

    @Transactional(readOnly = true)
    public List<WorkshopItem> dossierItems(UUID eventId) {
        return workshopItems(eventId, null).stream().filter(i -> "YES".equals(i.damageState())).toList();
    }

    @Transactional(readOnly = true)
    public List<PurchaseItem> purchases(UUID eventId) {
        return purchaseItems(eventId);
    }

    private WorkshopEventDetail buildDetail(UUID eventId, Actor actor) {
        EventRow event = requireEvent(eventId);
        List<WorkshopItem> main = workshopItems(eventId, null);
        List<WorkshopThirdParty> thirds = jdbc.query("""
                select id,name,vehicle_name,vehicle_plate,vehicle_model,vehicle_color,vehicle_category
                  from nh_event_third_parties where event_id=? order by created_at
                """, (rs,rowNum) -> {
            UUID id = uuid(rs,"id");
            return new WorkshopThirdParty(id, rs.getString("name"), rs.getString("vehicle_name"), rs.getString("vehicle_plate"),
                    rs.getString("vehicle_model"), rs.getString("vehicle_color"), rs.getString("vehicle_category"), workshopItems(eventId,id));
        }, eventId);
        List<PurchaseItem> purchases = purchaseItems(eventId);
        return new WorkshopEventDetail(
                event.id(),event.protocol(),event.eventType(),event.status(),event.occurredAt(),event.location(),event.description(),
                event.associateName(),event.associateNumber(),event.planName(),event.planCode(),event.planMonthlyAmount(),event.participationAmount(),event.coverageNotes(),
                event.vehiclePlate(),event.vehicleBrand(),event.vehicleModel(),event.vehicleVersion(),event.vehicleCategory(),event.vehicleSubtype(),
                event.vehicleYear(),event.vehicleColor(),event.vehicleFuel(),event.vehicleTransmission(),event.vehicleOdometer(),event.fipeCode(),event.fipeValue(),event.chassis(),
                event.createdAt(),event.workshopStartedAt(),event.workshopStartedBy(),event.workshopCompletedAt(),event.workshopCompletedBy(),event.acceptedAt(),event.publicAcceptanceToken(),main,thirds,purchases,
                event.workshopCompletedAt()!=null,!"FINALIZED".equals(event.status())
        );
    }

    private void ensureWorkshopChecklists(EventRow event) {
        if (event.workshopCompletedAt() != null) return; // preserva o checklist histórico já concluído
        ensureTarget(event.id(), null, event.vehicleCategory());
        jdbc.query("select id,vehicle_category from nh_event_third_parties where event_id=?", rs -> {
            while (rs.next()) ensureTarget(event.id(), uuid(rs,"id"), rs.getString("vehicle_category"));
        }, event.id());
    }

    private void ensureTarget(UUID eventId, UUID thirdPartyId, String category) {
        // Remove somente itens antigos/inativos ainda não avaliados. Itens já avaliados são preservados para auditoria histórica.
        if (thirdPartyId == null) {
            jdbc.update("""
                    delete from nh_event_checklist_items i
                     where i.event_id=? and i.third_party_id is null and i.damage_state='UNASSESSED'
                       and i.template_item_id is not null
                       and not exists (select 1 from nh_event_checklist_template_items t where t.id=i.template_item_id and t.active=true and t.vehicle_category=?)
                    """, eventId, category);
        } else {
            jdbc.update("""
                    delete from nh_event_checklist_items i
                     where i.event_id=? and i.third_party_id=? and i.damage_state='UNASSESSED'
                       and i.template_item_id is not null
                       and not exists (select 1 from nh_event_checklist_template_items t where t.id=i.template_item_id and t.active=true and t.vehicle_category=?)
                    """, eventId, thirdPartyId, category);
        }
        List<TemplateRow> templates = jdbc.query("""
                select id,section,label,sort_order from nh_event_checklist_template_items
                 where vehicle_category=? and active=true order by sort_order,label
                """, (rs,rowNum)->new TemplateRow(uuid(rs,"id"),rs.getString("section"),rs.getString("label"),rs.getInt("sort_order")), category);
        for (TemplateRow t : templates) {
            Integer count = thirdPartyId == null
                    ? jdbc.queryForObject("select count(*) from nh_event_checklist_items where event_id=? and third_party_id is null and template_item_id=?", Integer.class, eventId, t.id())
                    : jdbc.queryForObject("select count(*) from nh_event_checklist_items where event_id=? and third_party_id=? and template_item_id=?", Integer.class, eventId, thirdPartyId, t.id());
            if (count != null && count > 0) continue;
            jdbc.update("""
                    insert into nh_event_checklist_items(
                        id,event_id,third_party_id,template_item_id,section,label,sort_order,reported_state,analysis_state,
                        damage_state,repair_action,updated_at
                    ) values (?,?,?,?,?,?,?,'UNANSWERED','UNASSESSED','UNASSESSED','NONE',now())
                    """, UUID.randomUUID(),eventId,thirdPartyId,t.id(),t.section(),t.label(),t.sortOrder());
        }
    }

    private void syncAllPurchases(UUID eventId, String actor) {
        List<UUID> ids = jdbc.query("select id from nh_event_checklist_items where event_id=?", (rs,rowNum)->uuid(rs,"id"), eventId);
        for (UUID id : ids) syncPurchaseForItem(eventId,id,actor);
    }

    private void syncPurchaseForItem(UUID eventId, UUID checklistItemId, String actor) {
        ItemForPurchase item = jdbc.query("""
                select id,third_party_id,label,damage_state,repair_action from nh_event_checklist_items where id=? and event_id=?
                """, rs -> rs.next() ? new ItemForPurchase(uuid(rs,"id"), nullableUuid(rs,"third_party_id"), rs.getString("label"), rs.getString("damage_state"), rs.getString("repair_action")) : null,
                checklistItemId,eventId);
        if (item == null) return;
        if ("YES".equals(item.damageState()) && "REPLACE".equals(item.repairAction())) {
            jdbc.update("""
                    insert into nh_event_purchase_items(id,event_id,checklist_item_id,third_party_id,item_label,status,created_at,updated_at,updated_by)
                    values (?,?,?,?,?,'REQUESTED',now(),now(),?)
                    on conflict (checklist_item_id) do update set item_label=excluded.item_label,third_party_id=excluded.third_party_id,updated_at=now(),updated_by=excluded.updated_by
                    """, UUID.randomUUID(),eventId,item.id(),item.thirdPartyId(),item.label(),actor);
        } else {
            jdbc.update("delete from nh_event_purchase_items where checklist_item_id=? and event_id=?", checklistItemId,eventId);
        }
    }

    private List<WorkshopItem> workshopItems(UUID eventId, UUID thirdPartyId) {
        String condition = thirdPartyId == null ? "third_party_id is null" : "third_party_id=?";
        String sql = "select i.id,i.third_party_id,i.section,i.label,i.sort_order,i.damage_state,i.repair_action,i.workshop_notes,i.workshop_updated_by,i.workshop_updated_at " +
                "from nh_event_checklist_items i left join nh_event_checklist_template_items t on t.id=i.template_item_id " +
                "where i.event_id=? and " + condition.replace("third_party_id", "i.third_party_id") +
                " and (coalesce(t.active,true)=true or i.damage_state<>'UNASSESSED') order by i.sort_order,i.label";
        Object[] args = thirdPartyId == null ? new Object[]{eventId} : new Object[]{eventId,thirdPartyId};
        return jdbc.query(sql,(rs,rowNum)->new WorkshopItem(
                uuid(rs,"id"),nullableUuid(rs,"third_party_id"),rs.getString("section"),rs.getString("label"),rs.getInt("sort_order"),
                rs.getString("damage_state"),rs.getString("repair_action"),rs.getString("workshop_notes"),rs.getString("workshop_updated_by"),offset(rs,"workshop_updated_at")
        ),args);
    }

    private List<PurchaseItem> purchaseItems(UUID eventId) {
        return jdbc.query("""
                select p.id,p.checklist_item_id,p.third_party_id,p.item_label,p.supplier,p.amount,p.delivery_deadline,p.status,p.notes,p.updated_by,p.updated_at,p.details_saved_at,p.details_saved_by
                  from nh_event_purchase_items p
                  join nh_event_checklist_items i on i.id=p.checklist_item_id and i.event_id=p.event_id
                 where p.event_id=? and i.damage_state='YES' and i.repair_action='REPLACE'
                 order by p.created_at,p.item_label
                """, (rs,rowNum)->new PurchaseItem(
                uuid(rs,"id"),uuid(rs,"checklist_item_id"),nullableUuid(rs,"third_party_id"),rs.getString("item_label"),rs.getString("supplier"),
                rs.getBigDecimal("amount"), rs.getObject("delivery_deadline", LocalDate.class), rs.getString("status"),rs.getString("notes"),rs.getString("updated_by"),offset(rs,"updated_at"),
                offset(rs,"details_saved_at"),rs.getString("details_saved_by")
        ),eventId);
    }

    private WorkshopQueueSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new WorkshopQueueSummary(uuid(rs,"id"),rs.getString("protocol"),rs.getString("event_type"),rs.getString("status"),
                rs.getString("associate_name"),rs.getString("associate_number"),rs.getString("vehicle_plate"),rs.getString("vehicle_model"),rs.getString("vehicle_category"),
                offset(rs,"occurred_at"),offset(rs,"created_at"),offset(rs,"workshop_started_at"),offset(rs,"workshop_completed_at"),
                rs.getInt("assessed_items"),rs.getInt("total_items"),rs.getInt("damaged_items"),rs.getInt("replace_items"),rs.getInt("purchase_items"),
                offset(rs,"accepted_at"),rs.getString("public_acceptance_token"));
    }

    private EventRow requireEvent(UUID eventId) {
        EventRow event = jdbc.query("""
                select id,protocol,event_type,status,occurred_at,location,description,associate_name,associate_number,
                       plan_name,plan_code,plan_monthly_amount,participation_amount,coverage_notes,
                       vehicle_plate,vehicle_brand,vehicle_model,vehicle_version,vehicle_category,vehicle_subtype,vehicle_year,vehicle_color,
                       vehicle_fuel,vehicle_transmission,vehicle_odometer,fipe_code,fipe_value,chassis,created_at,
                       workshop_started_at,workshop_started_by,workshop_completed_at,workshop_completed_by,accepted_at,public_acceptance_token
                  from nh_event_records where id=? and registration_completed_at is not null
                """, rs -> rs.next() ? new EventRow(uuid(rs,"id"),rs.getString("protocol"),rs.getString("event_type"),rs.getString("status"),offset(rs,"occurred_at"),
                rs.getString("location"),rs.getString("description"),rs.getString("associate_name"),rs.getString("associate_number"),
                rs.getString("plan_name"),rs.getString("plan_code"),rs.getBigDecimal("plan_monthly_amount"),rs.getBigDecimal("participation_amount"),rs.getString("coverage_notes"),
                rs.getString("vehicle_plate"),rs.getString("vehicle_brand"),rs.getString("vehicle_model"),rs.getString("vehicle_version"),rs.getString("vehicle_category"),
                rs.getString("vehicle_subtype"),rs.getString("vehicle_year"),rs.getString("vehicle_color"),rs.getString("vehicle_fuel"),rs.getString("vehicle_transmission"),
                nullableLong(rs,"vehicle_odometer"),rs.getString("fipe_code"),rs.getBigDecimal("fipe_value"),rs.getString("chassis"),
                offset(rs,"created_at"),offset(rs,"workshop_started_at"),rs.getString("workshop_started_by"),offset(rs,"workshop_completed_at"),rs.getString("workshop_completed_by"),
                offset(rs,"accepted_at"),rs.getString("public_acceptance_token")) : null,eventId);
        if (event == null) throw new IllegalArgumentException("Evento não encontrado ou ainda não enviado para a oficina.");
        return event;
    }

    private Actor actor(PortalPrincipal principal) {
        var session = portalUserService.session(principal.username());
        return new Actor(session.username(), firstNonBlank(session.displayName(), session.consultantName(), session.username()), principal.role());
    }

    private static void assertWorkshopAccess(Actor actor) {
        if (actor.role()!=PortalRole.WORKSHOP_MANAGER && actor.role()!=PortalRole.SUPERVISION_ANALYSIS && actor.role()!=PortalRole.ADMIN) {
            throw new IllegalArgumentException("Este usuário não possui acesso à área da oficina.");
        }
    }

    private static void assertEditable(EventRow event) {
        if ("FINALIZED".equals(event.status())) throw new IllegalArgumentException("O evento já foi finalizado e está disponível apenas para consulta.");
    }

    private void audit(UUID eventId, Actor actor, String action, String entityType, UUID entityId, String details) {
        jdbc.update("insert into nh_event_audit_logs(id,event_id,actor_username,actor_name,action,entity_type,entity_id,details,created_at) values (?,?,?,?,?,?,?,?,now())",
                UUID.randomUUID(),eventId,actor.username(),actor.name(),action,entityType,entityId,clean(details,8000));
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String canonicalLabel(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return normalized.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private static String clean(String value, int max) { if (value==null) return null; String s=value.trim().replaceAll("\\s+"," "); return s.isBlank()?null:s.substring(0,Math.min(max,s.length())); }
    private static String firstNonBlank(String... values) { for(String v:values) if(v!=null&&!v.isBlank()) return v.trim(); return "Oficina"; }
    private static UUID uuid(ResultSet rs,String column) throws SQLException { Object v=rs.getObject(column); return v instanceof UUID u?u:UUID.fromString(String.valueOf(v)); }
    private static UUID nullableUuid(ResultSet rs,String column) throws SQLException { Object v=rs.getObject(column); if(v==null)return null; return v instanceof UUID u?u:UUID.fromString(String.valueOf(v)); }
    private static Long nullableLong(ResultSet rs,String column) throws SQLException { Object v=rs.getObject(column); return v==null?null:rs.getLong(column); }
    private static OffsetDateTime offset(ResultSet rs,String column) throws SQLException { Object v=rs.getObject(column); if(v==null)return null; if(v instanceof OffsetDateTime o)return o; if(v instanceof java.sql.Timestamp t)return t.toInstant().atOffset(java.time.ZoneOffset.UTC); return OffsetDateTime.parse(String.valueOf(v)); }

    private record Actor(String username,String name,PortalRole role) {}
    private record ExistingItem(UUID id,String label) {}
    private record TemplateRow(UUID id,String section,String label,int sortOrder) {}
    private record ItemForPurchase(UUID id,UUID thirdPartyId,String label,String damageState,String repairAction) {}
    private record EventRow(UUID id,String protocol,String eventType,String status,OffsetDateTime occurredAt,String location,String description,
                            String associateName,String associateNumber,String planName,String planCode,BigDecimal planMonthlyAmount,BigDecimal participationAmount,String coverageNotes,
                            String vehiclePlate,String vehicleBrand,String vehicleModel,String vehicleVersion,String vehicleCategory,String vehicleSubtype,
                            String vehicleYear,String vehicleColor,String vehicleFuel,String vehicleTransmission,Long vehicleOdometer,String fipeCode,BigDecimal fipeValue,String chassis,
                            OffsetDateTime createdAt,OffsetDateTime workshopStartedAt,String workshopStartedBy,OffsetDateTime workshopCompletedAt,String workshopCompletedBy,
                            OffsetDateTime acceptedAt,String publicAcceptanceToken) {}
}
