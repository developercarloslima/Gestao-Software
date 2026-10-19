package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.ProcurementDtos.*;
import br.com.nh.cotacao.security.PortalPrincipal;
import br.com.nh.cotacao.security.PortalRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ProcurementService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("REQUESTED", "FINALIZED");

    private final JdbcTemplate jdbc;
    private final PortalUserService portalUserService;

    public ProcurementService(JdbcTemplate jdbc, PortalUserService portalUserService) {
        this.jdbc = jdbc;
        this.portalUserService = portalUserService;
    }

    @Transactional(readOnly = true)
    public List<PurchaseEventSummary> list(PortalPrincipal principal, String query) {
        Actor actor = actor(principal);
        assertAccess(actor);
        StringBuilder sql = new StringBuilder("""
            select e.id,e.protocol,e.associate_name,e.associate_number,e.vehicle_plate,e.vehicle_brand,e.vehicle_model,
                   e.vehicle_category,e.plan_name,e.workshop_completed_at,e.workshop_completed_by,
                   count(p.id) total_items,
                   count(*) filter (where p.details_saved_at is null) unfilled_items,
                   count(*) filter (where p.details_saved_at is not null and p.status <> 'FINALIZED') requested_items,
                   count(*) filter (where p.status = 'FINALIZED') finalized_items
              from nh_event_records e
              join nh_event_purchase_items p on p.event_id=e.id
              join nh_event_checklist_items i on i.id=p.checklist_item_id and i.event_id=p.event_id
             where e.workshop_completed_at is not null
               and i.damage_state='YES' and i.repair_action='REPLACE'
            """);
        List<Object> args = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            sql.append(" and (lower(e.protocol) like ? or lower(e.associate_name) like ? or lower(e.associate_number) like ? or lower(e.vehicle_plate) like ? or lower(e.vehicle_model) like ?)");
            for (int i = 0; i < 5; i++) args.add(pattern);
        }
        sql.append(" group by e.id,e.protocol,e.associate_name,e.associate_number,e.vehicle_plate,e.vehicle_brand,e.vehicle_model,e.vehicle_category,e.plan_name,e.workshop_completed_at,e.workshop_completed_by order by e.workshop_completed_at desc,e.created_at desc");
        return jdbc.query(sql.toString(), (rs,n) -> summary(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public PurchaseEventDetail detail(UUID eventId, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertAccess(actor);
        PurchaseEventSummary summary = jdbc.query("""
            select e.id,e.protocol,e.associate_name,e.associate_number,e.vehicle_plate,e.vehicle_brand,e.vehicle_model,
                   e.vehicle_category,e.plan_name,e.workshop_completed_at,e.workshop_completed_by,
                   count(p.id) total_items,
                   count(*) filter (where p.details_saved_at is null) unfilled_items,
                   count(*) filter (where p.details_saved_at is not null and p.status <> 'FINALIZED') requested_items,
                   count(*) filter (where p.status = 'FINALIZED') finalized_items
              from nh_event_records e
              join nh_event_purchase_items p on p.event_id=e.id
              join nh_event_checklist_items i on i.id=p.checklist_item_id and i.event_id=p.event_id
             where e.id=? and e.workshop_completed_at is not null
               and i.damage_state='YES' and i.repair_action='REPLACE'
             group by e.id,e.protocol,e.associate_name,e.associate_number,e.vehicle_plate,e.vehicle_brand,e.vehicle_model,e.vehicle_category,e.plan_name,e.workshop_completed_at,e.workshop_completed_by
            """, rs -> rs.next() ? summary(rs) : null, eventId);
        if (summary == null) throw new IllegalArgumentException("Evento sem compras liberadas ou não encontrado.");
        return new PurchaseEventDetail(
                summary.id(), summary.protocol(), summary.associateName(), summary.associateNumber(), summary.vehiclePlate(),
                summary.vehicleBrand(), summary.vehicleModel(), summary.vehicleCategory(), summary.planName(),
                summary.workshopCompletedAt(), summary.workshopCompletedBy(), summary.totalItems(), summary.unfilledItems(),
                summary.requestedItems(), summary.finalizedItems(), summary.bucket(), purchases(eventId)
        );
    }

    @Transactional
    public PurchaseEventDetail updatePurchase(UUID eventId, UUID purchaseId, UpdatePurchaseRequest request, PortalPrincipal principal) {
        Actor actor = actor(principal);
        assertAccess(actor);
        String supplier = clean(request.supplier(), 180);
        if (supplier == null) throw new IllegalArgumentException("Informe o fornecedor.");
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() < 0) throw new IllegalArgumentException("Informe um valor válido para a compra.");
        if (request.deliveryDeadline() == null) throw new IllegalArgumentException("Informe o prazo de entrega.");
        String status = request.status() == null || request.status().isBlank() ? "REQUESTED" : request.status().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(status)) throw new IllegalArgumentException("Status inválido. Use Solicitado ou Finalizado.");

        Integer active = jdbc.queryForObject("""
            select count(*)
              from nh_event_purchase_items p
              join nh_event_checklist_items i on i.id=p.checklist_item_id and i.event_id=p.event_id
              join nh_event_records e on e.id=p.event_id
             where p.id=? and p.event_id=? and e.workshop_completed_at is not null
               and i.damage_state='YES' and i.repair_action='REPLACE'
            """, Integer.class, purchaseId, eventId);
        if (active == null || active == 0) throw new IllegalArgumentException("Item de compra não encontrado ou não está mais marcado para troca.");

        int updated = jdbc.update("""
            update nh_event_purchase_items
               set supplier=?, amount=?, delivery_deadline=?, status=?, notes=?, updated_by=?, updated_at=now(),
                   details_saved_at=coalesce(details_saved_at,now()),
                   details_saved_by=coalesce(details_saved_by,?)
             where id=? and event_id=?
            """, supplier, amount, request.deliveryDeadline(), status, clean(request.notes(),3000), actor.name(), actor.name(), purchaseId, eventId);
        if (updated != 1) throw new IllegalArgumentException("Item de compra não encontrado.");
        audit(eventId, actor, "PURCHASE_UPDATED", purchaseId,
                "Compras do Evento atualizada: fornecedor=" + supplier + ", valor=" + amount + ", prazo=" + request.deliveryDeadline() + ", status=" + status + ".");
        return detail(eventId, principal);
    }

    private List<PurchaseItem> purchases(UUID eventId) {
        return jdbc.query("""
            select p.id,p.checklist_item_id,p.third_party_id,p.item_label,p.supplier,p.amount,p.delivery_deadline,p.status,p.notes,
                   p.updated_by,p.updated_at,p.details_saved_at,p.details_saved_by
              from nh_event_purchase_items p
              join nh_event_checklist_items i on i.id=p.checklist_item_id and i.event_id=p.event_id
             where p.event_id=? and i.damage_state='YES' and i.repair_action='REPLACE'
             order by p.created_at,p.item_label
            """, (rs,n) -> new PurchaseItem(
                uuid(rs,"id"), uuid(rs,"checklist_item_id"), nullableUuid(rs,"third_party_id"), rs.getString("item_label"),
                rs.getString("supplier"), rs.getBigDecimal("amount"), rs.getObject("delivery_deadline", LocalDate.class),
                rs.getString("status"), rs.getString("notes"), rs.getString("updated_by"), offset(rs,"updated_at"),
                offset(rs,"details_saved_at"), rs.getString("details_saved_by")
            ), eventId);
    }

    private PurchaseEventSummary summary(ResultSet rs) throws SQLException {
        int total = rs.getInt("total_items");
        int unfilled = rs.getInt("unfilled_items");
        int requested = rs.getInt("requested_items");
        int finalized = rs.getInt("finalized_items");
        String bucket = total > 0 && finalized == total ? "FINALIZED" : unfilled > 0 ? "NOT_FILLED" : "REQUESTED";
        return new PurchaseEventSummary(
                uuid(rs,"id"), rs.getString("protocol"), rs.getString("associate_name"), rs.getString("associate_number"),
                rs.getString("vehicle_plate"), rs.getString("vehicle_brand"), rs.getString("vehicle_model"), rs.getString("vehicle_category"),
                rs.getString("plan_name"), offset(rs,"workshop_completed_at"), rs.getString("workshop_completed_by"),
                total, unfilled, requested, finalized, bucket
        );
    }

    private Actor actor(PortalPrincipal principal) {
        var session = portalUserService.session(principal.username());
        return new Actor(session.username(), firstNonBlank(session.displayName(), session.consultantName(), session.username()), principal.role());
    }

    private static void assertAccess(Actor actor) {
        if (actor.role() != PortalRole.BUYER && actor.role() != PortalRole.ADMIN) {
            throw new IllegalArgumentException("Este usuário não possui acesso à Área do Financeiro.");
        }
    }

    private void audit(UUID eventId, Actor actor, String action, UUID entityId, String details) {
        jdbc.update("insert into nh_event_audit_logs(id,event_id,actor_username,actor_name,action,entity_type,entity_id,details,created_at) values (?,?,?,?,?,'PURCHASE_ITEM',?,?,now())",
                UUID.randomUUID(), eventId, actor.username(), actor.name(), action, entityId, clean(details,8000));
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String s = value.trim().replaceAll("\\s+", " ");
        return s.isBlank() ? null : s.substring(0, Math.min(max, s.length()));
    }
    private static String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v.trim(); return "Compras / Financeiro"; }
    private static UUID uuid(ResultSet rs, String column) throws SQLException { Object v=rs.getObject(column); return v instanceof UUID u?u:UUID.fromString(String.valueOf(v)); }
    private static UUID nullableUuid(ResultSet rs, String column) throws SQLException { Object v=rs.getObject(column); if(v==null)return null; return v instanceof UUID u?u:UUID.fromString(String.valueOf(v)); }
    private static OffsetDateTime offset(ResultSet rs, String column) throws SQLException { Object v=rs.getObject(column); if(v==null)return null; if(v instanceof OffsetDateTime o)return o; if(v instanceof java.sql.Timestamp t)return t.toInstant().atOffset(java.time.ZoneOffset.UTC); return OffsetDateTime.parse(String.valueOf(v)); }

    private record Actor(String username, String name, PortalRole role) {}
}
