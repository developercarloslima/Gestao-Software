package br.com.nh.cotacao.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** BI comercial auditável: aceite digital é um fato contratual distinto do
 * pagamento da adesão. Somente o par aceite + pagamento RECEIVED é venda.
 */
@Service
public class SalesTeamAnalyticsService {
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    public record Metrics(int sales, BigDecimal averageMonthlyTicket, Double delinquencyRate,
                          Double churnRate, Integer cancellations, Integer activeBase) {}
    public record Sale(UUID id, UUID consultantId, String consultantName, String associateName,
                       String plate, BigDecimal monthlyValue, OffsetDateTime acceptedAt,
                       OffsetDateTime paidAt, OffsetDateTime completedAt, String paymentMethod,
                       UUID teamId, String teamName) {}
    /** Sem assinatura não é venda; o cadastro continua visível como pendência operacional. */
    public record Pending(UUID id, UUID consultantId, String consultantName, String associateName,
                          String plate, BigDecimal monthlyValue, OffsetDateTime createdAt,
                          UUID teamId, String teamName, String status, boolean awaitingAcceptance,
                          boolean awaitingPayment) {}
    public record AcceptanceCounts(int total, int accepted, int pendingAcceptance, int inProgress,
                                   int pendingPayment) {}
    public record TeamPerformance(UUID id, String name, boolean active, int members, Metrics metrics,
                                  AcceptanceCounts acceptance) {}
    public record ConsultantPerformance(UUID id, String name, UUID currentTeamId, String currentTeamName, Metrics metrics,
                                        List<Sale> associates, List<Pending> pendingAssociates,
                                        AcceptanceCounts acceptance) {}
    public record DayPerformance(LocalDate day, int sales, BigDecimal averageMonthlyTicket) {}
    public record Report(LocalDate from, LocalDate to, Metrics company, int teamCount,
                         int consultantCount, int unassignedSales, List<TeamPerformance> teams,
                         List<ConsultantPerformance> consultants, List<DayPerformance> days,
                         List<Sale> sales, List<Pending> pending, AcceptanceCounts companyAcceptance,
                         long salesByBoleto, long salesByPix, long salesByOther,
                         String financialStatus, String salesDefinition) {}

    private final JdbcTemplate jdbc;

    public SalesTeamAnalyticsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Report report(LocalDate start, LocalDate end, UUID teamFilter, UUID consultantFilter) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate from = start == null ? today.withDayOfMonth(1) : start;
        LocalDate to = end == null ? today : end;
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365 * 5L)
            throw new IllegalArgumentException("Selecione um intervalo válido de até cinco anos.");

        OffsetDateTime beginAt = from.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime endAt = to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();
        List<Sale> allSales = jdbc.query("""
                WITH adhesion_paid AS (
                    SELECT DISTINCT ON (f.vehicle_id) f.vehicle_id, f.paid_at, f.payment_method
                      FROM financial_charges f
                     WHERE f.charge_type='ADHESION' AND f.status='RECEIVED' AND f.paid_at IS NOT NULL
                     ORDER BY f.vehicle_id, f.paid_at ASC
                )
                SELECT v.id,COALESCE(v.consultant_id,q.consultant_id) AS consultant_id,
                       c.name AS consultant_name,a.name AS associate_name,v.plate,v.monthly_value,
                       i.accepted_at,p.paid_at,GREATEST(i.accepted_at,p.paid_at) AS completed_at,
                       COALESCE(p.payment_method,'NAO_IDENTIFICADO') AS payment_method,
                       m.team_id,t.name AS team_name
                  FROM adhesion_paid p
                  JOIN commercial_vehicles v ON v.id=p.vehicle_id
                  JOIN associates a ON a.id=v.associate_id
                  LEFT JOIN quotations q ON q.id=v.quotation_id
                  LEFT JOIN consultants c ON c.id=COALESCE(v.consultant_id,q.consultant_id)
                  JOIN LATERAL (
                      SELECT x.accepted_at FROM inspection_requests x
                       WHERE x.quotation_id=v.quotation_id AND x.accepted_at IS NOT NULL
                         AND x.status='APPROVED' AND x.request_type='NEW_INSPECTION'
                       ORDER BY x.accepted_at DESC LIMIT 1
                  ) i ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT membership.team_id FROM sales_team_memberships membership
                       WHERE membership.consultant_id=COALESCE(v.consultant_id,q.consultant_id)
                         AND membership.member_role='CONSULTANT'
                         AND membership.joined_at<=i.accepted_at
                         AND (membership.left_at IS NULL OR membership.left_at>i.accepted_at)
                       ORDER BY membership.joined_at DESC,membership.id DESC LIMIT 1
                  ) m ON TRUE
                  LEFT JOIN sales_teams t ON t.id=m.team_id
                 WHERE GREATEST(i.accepted_at,p.paid_at)>=?
                   AND GREATEST(i.accepted_at,p.paid_at)<? AND v.status<>'CANCELLED'
                 ORDER BY completed_at DESC,v.id
                """, (rs, row) -> new Sale(
                rs.getObject("id",UUID.class),rs.getObject("consultant_id",UUID.class),
                rs.getString("consultant_name"),rs.getString("associate_name"),rs.getString("plate"),
                rs.getBigDecimal("monthly_value"),rs.getObject("accepted_at",OffsetDateTime.class),
                rs.getObject("paid_at",OffsetDateTime.class),rs.getObject("completed_at",OffsetDateTime.class),
                rs.getString("payment_method"),rs.getObject("team_id",UUID.class),
                rs.getString("team_name")),beginAt,endAt);
        // Pendências nascidas no período; aceites contam pela data efetiva da assinatura.
        // Registros ainda em cadastro não são apresentados como "aguardando aceite".
        List<Pending> allPending=jdbc.query("""
                SELECT i.id,COALESCE(i.consultant_id,q.consultant_id) AS consultant_id,
                       COALESCE(NULLIF(i.consultant_name,''),c.name) AS consultant_name,
                       i.associate_name,i.plate,q.monthly_value,i.created_at,i.status,
                       m.team_id,t.name AS team_name
                  FROM inspection_requests i
                  JOIN quotations q ON q.id=i.quotation_id
                  LEFT JOIN consultants c ON c.id=COALESCE(i.consultant_id,q.consultant_id)
                  LEFT JOIN LATERAL (
                      SELECT membership.team_id FROM sales_team_memberships membership
                       WHERE membership.consultant_id=COALESCE(i.consultant_id,q.consultant_id)
                         AND membership.member_role='CONSULTANT'
                         AND membership.joined_at<=i.created_at
                         AND (membership.left_at IS NULL OR membership.left_at>i.created_at)
                       ORDER BY membership.joined_at DESC,membership.id DESC LIMIT 1
                  ) m ON TRUE
                  LEFT JOIN sales_teams t ON t.id=m.team_id
                 WHERE i.created_at>=? AND i.created_at<? AND i.accepted_at IS NULL
                   AND i.request_type='NEW_INSPECTION'
                   AND i.status NOT IN ('REJECTED','CANCELLED','EXPIRED')
                 ORDER BY i.created_at DESC,i.id
                """,(rs,row)->new Pending(
                rs.getObject("id",UUID.class),rs.getObject("consultant_id",UUID.class),
                rs.getString("consultant_name"),rs.getString("associate_name"),rs.getString("plate"),
                rs.getBigDecimal("monthly_value"),rs.getObject("created_at",OffsetDateTime.class),
                rs.getObject("team_id",UUID.class),rs.getString("team_name"),rs.getString("status"),
                "APPROVED".equals(rs.getString("status")),false),beginAt,endAt);
        // Aceites já assinados mas sem adesão recebida continuam pendentes de
        // pagamento. Não aparecem em Vendas, nem geram veículo ativo.
        allPending.addAll(jdbc.query("""
                SELECT DISTINCT ON (v.id) v.id AS id,COALESCE(v.consultant_id,q.consultant_id) AS consultant_id,
                       c.name AS consultant_name,a.name AS associate_name,v.plate,v.monthly_value,
                       i.accepted_at AS created_at,m.team_id,t.name AS team_name
                  FROM commercial_vehicles v
                  JOIN associates a ON a.id=v.associate_id
                  JOIN quotations q ON q.id=v.quotation_id
                  JOIN inspection_requests i ON i.quotation_id=q.id
                  LEFT JOIN consultants c ON c.id=COALESCE(v.consultant_id,q.consultant_id)
                  LEFT JOIN LATERAL (
                      SELECT membership.team_id FROM sales_team_memberships membership
                       WHERE membership.consultant_id=COALESCE(v.consultant_id,q.consultant_id)
                         AND membership.member_role='CONSULTANT'
                         AND membership.joined_at<=i.accepted_at
                         AND (membership.left_at IS NULL OR membership.left_at>i.accepted_at)
                       ORDER BY membership.joined_at DESC,membership.id DESC LIMIT 1
                  ) m ON TRUE
                  LEFT JOIN sales_teams t ON t.id=m.team_id
                 WHERE i.accepted_at>=? AND i.accepted_at<?
                   AND i.status='APPROVED' AND i.request_type='NEW_INSPECTION'
                   AND v.status<>'CANCELLED'
                   AND NOT EXISTS (
                      SELECT 1 FROM financial_charges f
                       WHERE f.vehicle_id=v.id AND f.charge_type='ADHESION' AND f.status='RECEIVED'
                   )
                 ORDER BY v.id,i.accepted_at DESC
                """,(rs,row)->new Pending(
                rs.getObject("id",UUID.class),rs.getObject("consultant_id",UUID.class),
                rs.getString("consultant_name"),rs.getString("associate_name"),rs.getString("plate"),
                rs.getBigDecimal("monthly_value"),rs.getObject("created_at",OffsetDateTime.class),
                rs.getObject("team_id",UUID.class),rs.getString("team_name"),
                "AWAITING_ADHESION_PAYMENT",false,true),beginAt,endAt));
        List<Sale> sales = allSales.stream()
                .filter(sale -> teamFilter == null || teamFilter.equals(sale.teamId()))
                .filter(sale -> consultantFilter == null || consultantFilter.equals(sale.consultantId()))
                .toList();
        List<Pending> pending=allPending.stream()
                .filter(row->teamFilter==null || teamFilter.equals(row.teamId()))
                .filter(row->consultantFilter==null || consultantFilter.equals(row.consultantId()))
                .toList();

        record TeamInfo(UUID id, String name, boolean active, int members) {}
        List<TeamInfo> allTeams = jdbc.query("""
                SELECT t.id,t.name,t.active,
                       (SELECT count(*) FROM sales_team_memberships m
                         WHERE m.team_id=t.id AND m.left_at IS NULL) AS members
                  FROM sales_teams t ORDER BY t.name
                """, (rs,row) -> new TeamInfo(rs.getObject("id",UUID.class),rs.getString("name"),
                rs.getBoolean("active"),rs.getInt("members")));
        Map<UUID,List<Sale>> byTeam = new LinkedHashMap<>();
        for (Sale sale : sales) {
            if (sale.teamId()!=null) byTeam.computeIfAbsent(sale.teamId(),ignored->new ArrayList<>()).add(sale);
        }
        Map<UUID,List<Pending>> pendingByTeam=new LinkedHashMap<>();
        for(Pending item:pending) if(item.teamId()!=null)
            pendingByTeam.computeIfAbsent(item.teamId(),ignored->new ArrayList<>()).add(item);
        List<TeamPerformance> teams = allTeams.stream()
                .filter(team -> teamFilter == null || teamFilter.equals(team.id()))
                .filter(team -> team.active() || team.members()>0 || byTeam.containsKey(team.id()) || pendingByTeam.containsKey(team.id()))
                .map(team -> new TeamPerformance(team.id(),team.name(),team.active(),team.members(),
                        metrics(byTeam.getOrDefault(team.id(),List.of())),
                        acceptance(byTeam.getOrDefault(team.id(),List.of()),pendingByTeam.getOrDefault(team.id(),List.of()))))
                .sorted(Comparator.comparingInt((TeamPerformance team)->team.metrics().sales()).reversed()
                        .thenComparing(TeamPerformance::name)).toList();

        record ConsultantInfo(UUID id, String name, UUID currentTeamId, String currentTeamName) {}
        List<ConsultantInfo> people = jdbc.query("""
                SELECT c.id,c.name,
                       (SELECT m.team_id FROM sales_team_memberships m
                         WHERE m.consultant_id=c.id AND m.member_role='CONSULTANT' AND m.left_at IS NULL
                         LIMIT 1) AS current_team_id,
                       COALESCE((SELECT string_agg(t.name, ', ' ORDER BY t.name)
                           FROM sales_team_memberships m JOIN sales_teams t ON t.id=m.team_id
                           WHERE m.consultant_id=c.id AND m.left_at IS NULL), 'Sem equipe') AS current_team
                  FROM consultants c
                 WHERE c.active=true AND c.collaborator_role='CONSULTANT'
                """,(rs,row)->new ConsultantInfo(rs.getObject("id",UUID.class),
                rs.getString("name"),rs.getObject("current_team_id",UUID.class),
                rs.getString("current_team")));
        Map<UUID,List<Sale>> byConsultant=new LinkedHashMap<>();
        for (Sale sale : sales) {
            if (sale.consultantId()!=null)
                byConsultant.computeIfAbsent(sale.consultantId(),ignored->new ArrayList<>()).add(sale);
        }
        Map<UUID,List<Pending>> pendingByConsultant=new LinkedHashMap<>();
        for(Pending item:pending) if(item.consultantId()!=null)
            pendingByConsultant.computeIfAbsent(item.consultantId(),ignored->new ArrayList<>()).add(item);
        Map<UUID,ConsultantInfo> peopleById=new LinkedHashMap<>();
        for (ConsultantInfo person: people) peopleById.put(person.id(),person);
        java.util.Set<UUID> currentTeamMembers = teamFilter==null ? java.util.Set.of() :
                new java.util.HashSet<>(jdbc.query("""
                        SELECT consultant_id FROM sales_team_memberships
                         WHERE team_id=? AND consultant_id IS NOT NULL AND left_at IS NULL
                        """,(rs,row)->rs.getObject("consultant_id",UUID.class),teamFilter));
        List<ConsultantPerformance> consultantResults=new ArrayList<>();
        for (ConsultantInfo person: people) {
            if (consultantFilter!=null && !consultantFilter.equals(person.id())) continue;
            boolean currentlyInTeam=teamFilter==null || currentTeamMembers.contains(person.id());
            List<Sale> personSales=byConsultant.getOrDefault(person.id(),List.of());
            List<Pending> personPending=pendingByConsultant.getOrDefault(person.id(),List.of());
            if (!currentlyInTeam && personSales.isEmpty() && personPending.isEmpty()) continue;
            consultantResults.add(new ConsultantPerformance(person.id(),person.name(),person.currentTeamId(),person.currentTeamName(),
                    metrics(personSales),personSales,personPending,acceptance(personSales,personPending)));
        }
        // Um consultor apagado/inativo continua aparecendo para auditoria de vendas antigas.
        for (Map.Entry<UUID,List<Sale>> entry:byConsultant.entrySet()) {
            if (peopleById.containsKey(entry.getKey())) continue;
            Sale first=entry.getValue().get(0);
            consultantResults.add(new ConsultantPerformance(entry.getKey(),first.consultantName(),null,"Histórico",
                    metrics(entry.getValue()),entry.getValue(),pendingByConsultant.getOrDefault(entry.getKey(),List.of()),
                    acceptance(entry.getValue(),pendingByConsultant.getOrDefault(entry.getKey(),List.of()))));
        }
        // Consultor inativo com pendências permanece rastreável no BI.
        for(Map.Entry<UUID,List<Pending>> entry:pendingByConsultant.entrySet()) {
            if(peopleById.containsKey(entry.getKey()) || byConsultant.containsKey(entry.getKey()))continue;
            Pending first=entry.getValue().get(0);
            consultantResults.add(new ConsultantPerformance(entry.getKey(),first.consultantName(),null,"Histórico",
                    metrics(List.of()),List.of(),entry.getValue(),acceptance(List.of(),entry.getValue())));
        }
        consultantResults.sort(Comparator.comparingInt((ConsultantPerformance c)->c.metrics().sales()).reversed()
                .thenComparing(ConsultantPerformance::name,Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        Map<LocalDate,List<Sale>> perDay=new TreeMap<>();
        for (Sale sale:sales) {
            LocalDate day=sale.completedAt().atZoneSameInstant(ZONE).toLocalDate();
            perDay.computeIfAbsent(day,ignored->new ArrayList<>()).add(sale);
        }
        List<DayPerformance> days=perDay.entrySet().stream()
                .map(entry->new DayPerformance(entry.getKey(),entry.getValue().size(),metrics(entry.getValue()).averageMonthlyTicket()))
                .toList();
        int countUnassigned=(int)sales.stream().filter(sale->sale.teamId()==null).count();
        int activeTeams=(int)allTeams.stream().filter(TeamInfo::active).count();
        int consultantsInScope=teamFilter==null ? people.size() : currentTeamMembers.size();
        long paidBoleto=sales.stream().filter(s->"BOLETO".equalsIgnoreCase(s.paymentMethod())).count();
        long paidPix=sales.stream().filter(s->"PIX".equalsIgnoreCase(s.paymentMethod())).count();
        return new Report(from,to,companyMetrics(sales,teamFilter,consultantFilter),activeTeams,consultantsInScope,countUnassigned,
                teams,consultantResults,days,sales,pending,acceptance(sales,pending),
                paidBoleto,paidPix,sales.size()-paidBoleto-paidPix,
                "Inadimplência e base ativa são fotografias atuais de cobranças e veículos; churn e cancelamentos por período aguardam histórico auditável.",
                "Vendas = adesões recebidas e não estornadas com aceite digital confirmado. A venda entra na data em que ambas as condições foram cumpridas. Emissão de boleto não é venda.");
    }

    private AcceptanceCounts acceptance(List<Sale> sales,List<Pending> pending) {
        int awaiting=(int)pending.stream().filter(Pending::awaitingAcceptance).count();
        int waitingPayment=(int)pending.stream().filter(Pending::awaitingPayment).count();
        return new AcceptanceCounts(sales.size()+pending.size(),sales.size()+waitingPayment,
                awaiting,pending.size()-awaiting-waitingPayment,waitingPayment);
    }

    private Metrics metrics(List<Sale> sales) {
        if (sales.isEmpty()) return new Metrics(0,BigDecimal.ZERO,null,null,null,null);
        BigDecimal total=sales.stream().map(s->s.monthlyValue()==null?BigDecimal.ZERO:s.monthlyValue())
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        return new Metrics(sales.size(),total.divide(BigDecimal.valueOf(sales.size()),2,RoundingMode.HALF_UP),
                null,null,null,null);
    }

    /** Indicadores financeiros somente quando o recorte é toda a empresa.
     * Não exibir o total global como se fosse de uma equipe ou consultor.
     * Inadimplência: veículo ativo com parcela vencida há pelo menos 6 dias.
     */
    private Metrics companyMetrics(List<Sale> sales,UUID teamFilter,UUID consultantFilter) {
        Metrics salesMetrics=metrics(sales);
        if (teamFilter!=null || consultantFilter!=null) return salesMetrics;
        long[] snapshot=jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE v.status='ACTIVE'
                       AND EXISTS (
                           SELECT 1 FROM financial_charges paid
                            WHERE paid.vehicle_id=v.id AND paid.charge_type='ADHESION'
                              AND paid.status='RECEIVED')) AS active_count,
                       COUNT(*) FILTER (WHERE v.status='ACTIVE'
                       AND EXISTS (
                           SELECT 1 FROM financial_charges paid
                            WHERE paid.vehicle_id=v.id AND paid.charge_type='ADHESION'
                              AND paid.status='RECEIVED')
                       AND EXISTS (
                           SELECT 1 FROM financial_charges late
                            WHERE late.vehicle_id=v.id AND late.charge_type IN ('MONTHLY','CLOSING')
                              AND late.status IN ('PENDING','OVERDUE','ERROR')
                              AND late.due_date<(NOW() AT TIME ZONE 'America/Sao_Paulo')::date-6
                       )) AS late_count
                  FROM commercial_vehicles v
                """,(rs,row)->new long[]{rs.getLong("active_count"),rs.getLong("late_count")});
        long active=snapshot==null?0:snapshot[0];
        long late=snapshot==null?0:snapshot[1];
        Double delinquency=active==0?0.0:BigDecimal.valueOf(late*100.0/active)
                .setScale(2,RoundingMode.HALF_UP).doubleValue();
        return new Metrics(salesMetrics.sales(),salesMetrics.averageMonthlyTicket(),
                delinquency,null,null,Math.toIntExact(active));
    }

    /** CSVs independentes podem ser importados no Power BI Desktop sem credenciais expostas. */
    @Transactional(readOnly = true)
    public byte[] csv(LocalDate from,LocalDate to,UUID teamId,UUID consultantId,String dataset) {
        Report report=report(from,to,teamId,consultantId);
        StringBuilder csv=new StringBuilder("\uFEFF");
        switch (dataset) {
            case "equipes" -> {
                csv.append("Equipe ID;Equipe;Ativa;Consultores e participantes;Vendas pagas;Ticket medio mensal (BRL);Total;Aceites;Pendentes de aceite;Em cadastro;Aguardando adesao\r\n");
                report.teams().forEach(row->line(csv,row.id(),row.name(),row.active(),row.members(),row.metrics().sales(),
                        row.metrics().averageMonthlyTicket(),row.acceptance().total(),row.acceptance().accepted(),
                        row.acceptance().pendingAcceptance(),row.acceptance().inProgress(),row.acceptance().pendingPayment()));
            }
            case "consultores" -> {
                csv.append("Consultor ID;Consultor;Equipe atual ID;Equipe atual;Vendas pagas;Ticket medio mensal (BRL);Total;Aceites;Pendentes de aceite;Em cadastro;Aguardando adesao\r\n");
                report.consultants().forEach(row->line(csv,row.id(),row.name(),row.currentTeamId(),row.currentTeamName(),row.metrics().sales(),
                        row.metrics().averageMonthlyTicket(),row.acceptance().total(),row.acceptance().accepted(),
                        row.acceptance().pendingAcceptance(),row.acceptance().inProgress(),row.acceptance().pendingPayment()));
            }
            case "vendas" -> {
                csv.append("Veiculo;Data do aceite;Pagamento adesao;Venda concluida;Meio de pagamento;Equipe ID;Equipe;Consultor ID;Consultor;Associado;Placa;Mensalidade (BRL)\r\n");
                report.sales().forEach(row->line(csv,row.id(),row.acceptedAt(),row.paidAt(),row.completedAt(),row.paymentMethod(),row.teamId(),row.teamName(),row.consultantId(),row.consultantName(),
                        row.associateName(),row.plate(),row.monthlyValue()));
            }
            case "diario" -> {
                csv.append("Data;Vendas;Ticket medio mensal (BRL)\r\n");
                report.days().forEach(row->line(csv,row.day(),row.sales(),row.averageMonthlyTicket()));
            }
            case "aceites" -> {
                csv.append("Vistoria ID;Consultor ID;Consultor;Equipe ID;Equipe;Associado;Placa;Data;Situação;Mensalidade (BRL)\r\n");
                report.sales().forEach(row->line(csv,row.id(),row.consultantId(),row.consultantName(),row.teamId(),row.teamName(),
                        row.associateName(),row.plate(),row.acceptedAt(),"ACEITO E PAGO",row.monthlyValue()));
                report.pending().forEach(row->line(csv,row.id(),row.consultantId(),row.consultantName(),row.teamId(),row.teamName(),
                        row.associateName(),row.plate(),row.createdAt(),row.awaitingPayment()?"ADESAO PENDENTE":row.awaitingAcceptance()?"PENDENTE DE ACEITE":"EM CADASTRO",row.monthlyValue()));
            }
            default -> throw new IllegalArgumentException("Escolha equipes, consultores, vendas, aceites ou diario para exportar.");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void line(StringBuilder csv,Object...fields) {
        for (int i=0;i<fields.length;i++) {
            if (i>0) csv.append(';');
            String value=fields[i]==null?"":String.valueOf(fields[i]);
            // Evita execução de fórmulas quando alguém abrir o CSV em uma planilha.
            if (!value.isBlank() && "=+-@\t\r".indexOf(value.charAt(0))>=0) value="'"+value;
            csv.append('"').append(value.replace("\"","\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
