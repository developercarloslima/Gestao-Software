package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.CatalogChangeAudit;
import br.com.nh.cotacao.repository.CatalogChangeAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SalesTeamService {
    public record Team(UUID id, String name, boolean active, long memberCount, OffsetDateTime createdAt) {}
    public record Candidate(String key, String name, String role, boolean selected, String currentTeams) {}

    private final JdbcTemplate jdbc;
    private final CatalogChangeAuditRepository audit;

    public SalesTeamService(JdbcTemplate jdbc, CatalogChangeAuditRepository audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Team> list() {
        return jdbc.query("""
                SELECT t.id,t.name,t.active,t.created_at,
                       (SELECT count(*) FROM sales_team_memberships m
                         WHERE m.team_id=t.id AND m.left_at IS NULL) AS member_count
                  FROM sales_teams t WHERE t.deleted_at IS NULL
                 ORDER BY t.active DESC,t.name ASC
                """, (rs, row) -> new Team(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("active"),
                rs.getLong("member_count"), rs.getObject("created_at", OffsetDateTime.class)));
    }

    @Transactional
    public Team create(String rawName, String username) {
        String name = cleanName(rawName);
        String normalized = normalize(name);
        if (nameExists(normalized, null)) throw new IllegalArgumentException("Já existe uma equipe com esse nome.");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sales_teams(id,name,normalized_name) VALUES(?,?,?)", id, name, normalized);
        log(id, "Equipe criada: " + name, null, name, username);
        return require(id);
    }

    /** Criação e seleção inicial ocorrem juntas: falha na seleção desfaz a equipe. */
    @Transactional
    public Team createWithMembers(String rawName, List<String> keys, String username) {
        Team created = create(rawName, username);
        replaceMembers(created.id(), keys, username);
        return require(created.id());
    }

    @Transactional
    public Team update(UUID id, String rawName, Boolean active, String username) {
        Team previous = require(id);
        String name = rawName == null ? previous.name() : cleanName(rawName);
        String normalized = normalize(name);
        if (nameExists(normalized, id)) throw new IllegalArgumentException("Já existe uma equipe com esse nome.");
        boolean enabled = active == null ? previous.active() : active;
        jdbc.update("UPDATE sales_teams SET name=?,normalized_name=?,active=?,updated_at=now() WHERE id=? AND deleted_at IS NULL",
                name, normalized, enabled, id);
        if (!enabled && previous.active()) {
            jdbc.update("UPDATE sales_team_memberships SET left_at=now() WHERE team_id=? AND left_at IS NULL", id);
        }
        log(id, "Equipe alterada: " + name,
                previous.name() + " / ativa=" + previous.active(), name + " / ativa=" + enabled, username);
        return require(id);
    }

    /** Exclusão lógica: consultas e vendas históricas continuam auditáveis. */
    @Transactional
    public void delete(UUID id, String username) {
        Team previous = require(id);
        jdbc.update("UPDATE sales_team_memberships SET left_at=now() WHERE team_id=? AND left_at IS NULL", id);
        jdbc.update("UPDATE sales_teams SET active=false,deleted_at=now(),updated_at=now() WHERE id=? AND deleted_at IS NULL", id);
        log(id, "Equipe excluída: " + previous.name(), previous.name(), "Arquivo histórico preservado", username);
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID teamId) {
        if (teamId != null) require(teamId);
        List<Candidate> result = new ArrayList<>(jdbc.query("""
                SELECT c.id,c.name,c.collaborator_role,
                       EXISTS (SELECT 1 FROM sales_team_memberships m
                               WHERE m.team_id=? AND m.consultant_id=c.id AND m.left_at IS NULL) AS selected,
                       COALESCE((SELECT string_agg(t.name, ', ' ORDER BY t.name)
                           FROM sales_team_memberships m JOIN sales_teams t ON t.id=m.team_id
                           WHERE m.consultant_id=c.id AND m.left_at IS NULL), '') AS current_teams
                  FROM consultants c
                 WHERE c.active=true AND c.collaborator_role IN ('CONSULTANT','ANALYST','SUPERVISION_ANALYSIS')
                 ORDER BY CASE c.collaborator_role WHEN 'CONSULTANT' THEN 0 WHEN 'ANALYST' THEN 1 ELSE 2 END,c.name
                """, (rs, row) -> new Candidate(
                "C:" + rs.getObject("id", UUID.class),rs.getString("name"),rs.getString("collaborator_role"),
                rs.getBoolean("selected"),rs.getString("current_teams")), teamId));
        result.addAll(jdbc.query("""
                SELECT u.id,COALESCE(NULLIF(u.display_name,''),u.username) AS name,
                       EXISTS (SELECT 1 FROM sales_team_memberships m
                               WHERE m.team_id=? AND m.portal_user_id=u.id AND m.left_at IS NULL) AS selected,
                       COALESCE((SELECT string_agg(t.name, ', ' ORDER BY t.name)
                           FROM sales_team_memberships m JOIN sales_teams t ON t.id=m.team_id
                           WHERE m.portal_user_id=u.id AND m.left_at IS NULL), '') AS current_teams
                  FROM portal_users u WHERE u.active=true AND u.role='ADMIN' ORDER BY name
                """, (rs, row) -> new Candidate(
                "A:" + rs.getObject("id", UUID.class),rs.getString("name"),"ADMIN",
                rs.getBoolean("selected"),rs.getString("current_teams")), teamId));
        return result;
    }

    @Transactional(readOnly = true)
    public List<Candidate> availableCandidates() {
        return candidates(null);
    }

    /** Salva seleção completa (checked/unchecked) em uma transação. */
    @Transactional
    public Team editWithMembers(UUID id, String name, List<String> keys, String username) {
        update(id,name,null,username);
        replaceMembers(id,keys,username);
        return require(id);
    }

    /** Salva seleção completa (checked/unchecked) em uma transação. */
    @Transactional
    public List<Candidate> replaceMembers(UUID teamId, List<String> keys, String username) {
        Team team = require(teamId);
        if (!team.active()) throw new IllegalArgumentException("Ative a equipe antes de incluir participantes.");
        if (keys == null) throw new IllegalArgumentException("Informe a seleção de participantes.");
        Set<String> selected = new HashSet<>(keys);
        if (selected.contains(null) || selected.size() != keys.size())
            throw new IllegalArgumentException("Seleção de participantes duplicada ou inválida.");

        List<Candidate> available = candidates(teamId);
        Map<String,Candidate> byKey = new HashMap<>();
        available.forEach(person -> byKey.put(person.key(), person));
        if (!byKey.keySet().containsAll(selected))
            throw new IllegalArgumentException("A seleção contém um participante inexistente ou inativo.");

        List<String> previouslySelected = available.stream().filter(Candidate::selected).map(Candidate::key).toList();
        for (String key : previouslySelected) {
            if (selected.contains(key)) continue;
            MemberIdentity member = parseKey(key);
            closeMembership(teamId, member);
        }
        // Contas inativas que já participavam também são removidas, para não deixar vínculos ocultos.
        jdbc.update("""
                UPDATE sales_team_memberships m SET left_at=now()
                 WHERE m.team_id=? AND m.left_at IS NULL
                   AND (m.consultant_id IN (SELECT id FROM consultants WHERE active=false)
                        OR m.portal_user_id IN (SELECT id FROM portal_users WHERE active=false))
                """, teamId);

        for (String key : selected) {
            if (previouslySelected.contains(key)) continue;
            Candidate candidate = byKey.get(key);
            MemberIdentity member = parseKey(key);
            if (!member.admin()) {
                jdbc.update("""
                        UPDATE sales_team_memberships SET left_at=now()
                         WHERE consultant_id=? AND member_role<>? AND left_at IS NULL
                        """, member.id(), candidate.role());
            }
            if ("CONSULTANT".equals(candidate.role())) {
                // Uma transferência fecha o vínculo anterior, mas não altera o histórico de vendas.
                jdbc.update("""
                        UPDATE sales_team_memberships SET left_at=now()
                         WHERE consultant_id=? AND member_role='CONSULTANT' AND left_at IS NULL
                        """, member.id());
            }
            jdbc.update("""
                    INSERT INTO sales_team_memberships(id,team_id,member_role,consultant_id,portal_user_id,assigned_by)
                    VALUES(?,?,?,?,?,?)
                    """, UUID.randomUUID(), teamId, candidate.role(),
                    member.admin() ? null : member.id(), member.admin() ? member.id() : null, username);
        }
        jdbc.update("UPDATE sales_teams SET updated_at=now() WHERE id=?", teamId);
        log(teamId, "Participantes alterados: " + team.name(),
                "Antes=" + previouslySelected.size(), "Depois=" + selected.size(), username);
        return candidates(teamId);
    }

    private void closeMembership(UUID teamId, MemberIdentity member) {
        jdbc.update(member.admin()
                ? "UPDATE sales_team_memberships SET left_at=now() WHERE team_id=? AND portal_user_id=? AND left_at IS NULL"
                : "UPDATE sales_team_memberships SET left_at=now() WHERE team_id=? AND consultant_id=? AND left_at IS NULL",
                teamId, member.id());
    }

    private record MemberIdentity(UUID id, boolean admin) {}

    private MemberIdentity parseKey(String key) {
        try {
            if (key == null || key.length() < 3 || key.charAt(1) != ':') throw new IllegalArgumentException();
            if (key.charAt(0) != 'C' && key.charAt(0) != 'A') throw new IllegalArgumentException();
            return new MemberIdentity(UUID.fromString(key.substring(2)), key.charAt(0) == 'A');
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Identificador de participante inválido.");
        }
    }

    private Team require(UUID id) {
        List<Team> matches = jdbc.query("""
                SELECT t.id,t.name,t.active,t.created_at,
                       (SELECT count(*) FROM sales_team_memberships m
                        WHERE m.team_id=t.id AND m.left_at IS NULL) AS member_count
                  FROM sales_teams t WHERE t.id=? AND t.deleted_at IS NULL
                """, (rs, row) -> new Team(rs.getObject("id", UUID.class),rs.getString("name"),
                rs.getBoolean("active"),rs.getLong("member_count"),rs.getObject("created_at", OffsetDateTime.class)), id);
        if (matches.isEmpty()) throw new IllegalArgumentException("Equipe não encontrada.");
        return matches.get(0);
    }

    private boolean nameExists(String normalized, UUID except) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM sales_teams WHERE deleted_at IS NULL AND normalized_name=? AND (?::uuid IS NULL OR id <> ?::uuid)",
                Integer.class, normalized, except, except);
        return count != null && count > 0;
    }

    private String cleanName(String value) {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (name.length() < 3 || name.length() > 120)
            throw new IllegalArgumentException("O nome da equipe deve ter entre 3 e 120 caracteres.");
        return name;
    }

    private String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private void log(UUID id, String title, String oldValue, String newValue, String username) {
        audit.save(CatalogChangeAudit.createText("SALES_TEAM", null, id.toString(),
                title, oldValue, newValue, username));
    }
}
