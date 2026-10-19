package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.CommissionBreakdown;
import br.com.nh.cotacao.entity.CollaboratorRole;
import br.com.nh.cotacao.entity.Consultant;
import br.com.nh.cotacao.repository.ConsultantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class ConsultantCommissionGuideService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final JdbcTemplate jdbc;
    private final ConsultantRepository consultants;

    public ConsultantCommissionGuideService(JdbcTemplate jdbc, ConsultantRepository consultants) {
        this.jdbc = jdbc;
        this.consultants = consultants;
    }

    @Transactional(readOnly = true)
    public CommissionBreakdown calculate(UUID consultantId, YearMonth month) {
        Consultant consultant = consultants.findById(consultantId)
                .filter(Consultant::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Consultor não encontrado."));
        Map<String, BigDecimal> rules = ruleValues();
        BaseStats stats = baseStats(consultantId, month);
        BigDecimal rate = rateFor(stats.paidNewSales(), rules);
        BigDecimal ladder = ladderFor(stats.paidNewSales(), rules);
        BigDecimal baseCommission = stats.activeBaseRevenue().multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal ladderMax = rules.getOrDefault("LADDER_50", new BigDecimal("3000"));
        BigDecimal ladderOrBase = baseCommission.compareTo(ladderMax) > 0 ? baseCommission : ladder;
        String mode = baseCommission.compareTo(ladderMax) > 0 ? "PERCENTUAL_BASE_ATIVA" : "REGUA_BOLETOS_BAIXADOS";

        BigDecimal moto = rules.getOrDefault("SALE_MOTORCYCLE", new BigDecimal("30")).multiply(BigDecimal.valueOf(stats.motorcycleSales()));
        BigDecimal light = rules.getOrDefault("SALE_LIGHT", new BigDecimal("50")).multiply(BigDecimal.valueOf(stats.lightSales()));
        BigDecimal utility = rules.getOrDefault("SALE_UTILITY_TRUCK", new BigDecimal("70")).multiply(BigDecimal.valueOf(stats.utilityTruckSales()));
        BigDecimal referral = referralRevenue(consultantId, month)
                .multiply(rules.getOrDefault("REFERRAL_RATE", new BigDecimal("3")))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal ranking = rankingBonus(consultantId, month, rules);
        String career = careerLevel(stats.activePayingBase());
        BigDecimal fuel = switch (career) {
            case "PRATA" -> rules.getOrDefault("FUEL_SILVER", new BigDecimal("200"));
            case "OURO" -> rules.getOrDefault("FUEL_GOLD", new BigDecimal("300"));
            case "DIAMANTE" -> rules.getOrDefault("FUEL_DIAMOND", new BigDecimal("400"));
            default -> BigDecimal.ZERO;
        };
        BigDecimal adhesion = adhesionCommission(consultantId, month)
                .multiply(rules.getOrDefault("ADHESION_RATE", new BigDecimal("100")))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal total = ladderOrBase.add(moto).add(light).add(utility).add(referral).add(ranking).add(fuel).add(adhesion)
                .setScale(2, RoundingMode.HALF_UP);

        return new CommissionBreakdown(month.toString(), stats.paidNewSales(), money(ladderOrBase), money(moto), money(light), money(utility),
                money(referral), money(ranking), money(fuel), money(adhesion), total, rate.setScale(2, RoundingMode.HALF_UP), mode,
                nextCareerMilestone(stats.activePayingBase()));
    }

    @Transactional(readOnly = true)
    public BigDecimal totalEarned(UUID consultantId) {
        YearMonth first = firstFinancialMonth(consultantId);
        if (first == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        YearMonth now = YearMonth.now();
        BigDecimal total = BigDecimal.ZERO;
        int guard = 0;
        for (YearMonth cursor = first; !cursor.isAfter(now) && guard++ < 180; cursor = cursor.plusMonths(1)) {
            total = total.add(calculate(consultantId, cursor).total());
        }
        return money(total);
    }

    @Transactional(readOnly = true)
    public long activePayingBase(UUID consultantId, YearMonth month) { return baseStats(consultantId, month).activePayingBase(); }
    @Transactional(readOnly = true)
    public BigDecimal activeBaseRevenue(UUID consultantId, YearMonth month) { return baseStats(consultantId, month).activeBaseRevenue(); }
    @Transactional(readOnly = true)
    public long sales(UUID consultantId, YearMonth month) { return baseStats(consultantId, month).paidNewSales(); }
    @Transactional(readOnly = true)
    public String career(UUID consultantId, YearMonth month) { return careerLevel(activePayingBase(consultantId, month)); }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> rules() {
        return jdbc.query("select rule_key,label,numeric_value,unit,enabled,updated_by,updated_at from consultant_commission_rules order by rule_key",
                (rs,row)->{
                    Map<String,Object> m=new LinkedHashMap<>();
                    m.put("key",rs.getString("rule_key"));m.put("label",rs.getString("label"));m.put("value",rs.getBigDecimal("numeric_value"));
                    m.put("unit",rs.getString("unit"));m.put("enabled",rs.getBoolean("enabled"));m.put("updatedBy",rs.getString("updated_by"));m.put("updatedAt",rs.getObject("updated_at"));
                    return m;
                });
    }

    @Transactional
    public void updateRule(String key, BigDecimal value, boolean enabled, String username) {
        if (key == null || key.isBlank() || value == null || value.signum() < 0) throw new IllegalArgumentException("Regra de comissão inválida.");
        int updated = jdbc.update("update consultant_commission_rules set numeric_value=?,enabled=?,updated_by=?,updated_at=now() where rule_key=?", value, enabled, username, key);
        if (updated == 0) throw new IllegalArgumentException("Regra de comissão não encontrada.");
    }

    @Transactional
    public void setReferrer(UUID consultantId, UUID referrerId) {
        if (consultantId != null && consultantId.equals(referrerId)) throw new IllegalArgumentException("O consultor não pode indicar a si mesmo.");
        if (referrerId != null && consultants.findById(referrerId).filter(Consultant::isActive).isEmpty()) throw new IllegalArgumentException("Consultor indicador não encontrado.");
        jdbc.update("update consultants set referrer_consultant_id=?,updated_at=now() where id=?", referrerId, consultantId);
    }

    private BaseStats baseStats(UUID consultantId, YearMonth month) {
        LocalDate start = month.atDay(1), end = month.plusMonths(1).atDay(1);
        OffsetDateTime startAt = start.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endAt = end.atStartOfDay().atOffset(ZoneOffset.UTC);
        long newSales = longValue("select count(distinct vehicle_id) from financial_charges where consultant_id=? and charge_type='CLOSING' and status='RECEIVED' and paid_at>=? and paid_at<?", consultantId, startAt, endAt);

        // Base ativa pagante = veículo ativo, com fechamento/mensalidade já paga e sem atraso superior a 6 dias.
        String activePredicate = """
              v.consultant_id=? and v.status='ACTIVE'
              and exists (select 1 from financial_charges ok where ok.vehicle_id=v.id and ok.charge_type in ('CLOSING','MONTHLY') and ok.status='RECEIVED')
              and not exists (select 1 from financial_charges late where late.vehicle_id=v.id and late.status='OVERDUE' and late.due_date < ?)
            """;
        LocalDate delinquencyCutoff = month.equals(YearMonth.now()) ? LocalDate.now().minusDays(6) : end.minusDays(7);
        long paying = longValue("select count(*) from commercial_vehicles v where "+activePredicate, consultantId, delinquencyCutoff);
        BigDecimal revenue = decimalValue("select coalesce(sum(v.monthly_value),0) from commercial_vehicles v where "+activePredicate, consultantId, delinquencyCutoff);
        long activeTotal = longValue("select count(*) from commercial_vehicles where consultant_id=? and status='ACTIVE'", consultantId);
        BigDecimal ticket = paying == 0 ? BigDecimal.ZERO : revenue.divide(BigDecimal.valueOf(paying),2,RoundingMode.HALF_UP);
        long moto = closingCategoryCount(consultantId,start,end,"MOTO");
        long util = closingCategoryCount(consultantId,start,end,"UTILITY");
        long light = Math.max(0,newSales-moto-util);
        return new BaseStats(newSales,paying,money(revenue),ticket,activeTotal,moto,light,util);
    }

    private long closingCategoryCount(UUID consultantId, LocalDate start, LocalDate end, String kind) {
        String predicate = switch(kind){
            case "MOTO" -> "upper(coalesce(q.category_code,'')) like 'MOTORCYCLE%' or upper(coalesce(q.category_code,'')) like '%SCOOTER%'";
            case "UTILITY" -> "upper(coalesce(q.category_code,'')) like '%UTILITY%' or upper(coalesce(q.category_code,'')) like '%TRUCK%' or upper(coalesce(q.category_code,'')) like '%CAMINH%'";
            default -> "1=0";
        };
        return longValue("select count(distinct fc.vehicle_id) from financial_charges fc join commercial_vehicles cv on cv.id=fc.vehicle_id left join quotations q on q.id=cv.quotation_id where fc.consultant_id=? and fc.charge_type='CLOSING' and fc.status='RECEIVED' and fc.paid_at>=? and fc.paid_at<? and ("+predicate+")", consultantId, start.atStartOfDay().atOffset(java.time.ZoneOffset.UTC), end.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }

    private BigDecimal referralRevenue(UUID consultantId, YearMonth month) {
        LocalDate cutoff = month.equals(YearMonth.now()) ? LocalDate.now().minusDays(6) : month.plusMonths(1).atDay(1).minusDays(7);
        return decimalValue("""
            select coalesce(sum(v.monthly_value),0)
              from commercial_vehicles v
              join consultants c on c.id=v.consultant_id
             where c.referrer_consultant_id=? and c.active=true and v.status='ACTIVE'
               and exists (select 1 from financial_charges ok where ok.vehicle_id=v.id and ok.charge_type in ('CLOSING','MONTHLY') and ok.status='RECEIVED')
               and not exists (select 1 from financial_charges late where late.vehicle_id=v.id and late.status='OVERDUE' and late.due_date < ?)
            """, consultantId, cutoff);
    }

    private BigDecimal adhesionCommission(UUID consultantId, YearMonth month) {
        LocalDate start=month.atDay(1),end=month.plusMonths(1).atDay(1);
        return decimalValue("select coalesce(sum(value),0) from financial_charges where consultant_id=? and charge_type='ADHESION' and status='RECEIVED' and billing_type<>'CASH_HAND' and paid_at>=? and paid_at<?", consultantId,start.atStartOfDay().atOffset(java.time.ZoneOffset.UTC),end.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
    }

    private BigDecimal rankingBonus(UUID consultantId, YearMonth month, Map<String,BigDecimal> rules) {
        List<Consultant> active = consultants.findByActiveTrueAndRoleOrderByNameAsc(CollaboratorRole.CONSULTANT);
        if(active.isEmpty()) return BigDecimal.ZERO;
        Map<UUID,BaseStats> stats=new HashMap<>();
        for(Consultant c:active) stats.put(c.getId(),baseStats(c.getId(),month));
        int salesRank=rank(active,consultantId,Comparator.<Consultant>comparingLong(c->stats.get(c.getId()).paidNewSales()).reversed().thenComparing(Consultant::getName));
        int defaultRank=rankFiltered(active,consultantId,c->adherence(stats.get(c.getId())).compareTo(new BigDecimal("80"))>=0,Comparator.<Consultant,BigDecimal>comparing(c->adherence(stats.get(c.getId()))).reversed().thenComparing(Consultant::getName));
        int ticketRank=rankFiltered(active,consultantId,c->stats.get(c.getId()).ticket().compareTo(new BigDecimal("90"))>=0,Comparator.<Consultant,BigDecimal>comparing(c->stats.get(c.getId()).ticket()).reversed().thenComparing(Consultant::getName));
        return money(rankValue("RANK_SALES_",salesRank,rules).add(rankValue("RANK_DEFAULT_",defaultRank,rules)).add(rankValue("RANK_TICKET_",ticketRank,rules)));
    }

    private BigDecimal adherence(BaseStats s){if(s.activeTotal()<=0)return BigDecimal.ZERO;return BigDecimal.valueOf(s.activePayingBase()).multiply(HUNDRED).divide(BigDecimal.valueOf(s.activeTotal()),2,RoundingMode.HALF_UP);}
    private int rank(List<Consultant> list,UUID id,Comparator<Consultant> comparator){List<Consultant> copy=new ArrayList<>(list);copy.sort(comparator);for(int i=0;i<copy.size();i++)if(copy.get(i).getId().equals(id))return i+1;return 0;}
    private int rankFiltered(List<Consultant> list,UUID id,java.util.function.Predicate<Consultant> filter,Comparator<Consultant> comparator){List<Consultant> copy=list.stream().filter(filter).sorted(comparator).toList();for(int i=0;i<copy.size();i++)if(copy.get(i).getId().equals(id))return i+1;return 0;}
    private BigDecimal rankValue(String prefix,int rank,Map<String,BigDecimal> rules){return rank>0?rules.getOrDefault(prefix+rank,BigDecimal.ZERO):BigDecimal.ZERO;}

    private BigDecimal rateFor(long sales,Map<String,BigDecimal> rules){if(sales>=61)return rules.getOrDefault("RATE_61_PLUS",new BigDecimal("8"));if(sales>=41)return rules.getOrDefault("RATE_41_60",new BigDecimal("7"));if(sales>=31)return rules.getOrDefault("RATE_31_40",new BigDecimal("6"));if(sales>=21)return rules.getOrDefault("RATE_21_30",new BigDecimal("5.5"));if(sales>=10)return rules.getOrDefault("RATE_10_20",new BigDecimal("5"));return BigDecimal.ZERO;}
    private BigDecimal ladderFor(long sales,Map<String,BigDecimal> rules){if(sales>=50)return rules.getOrDefault("LADDER_50",new BigDecimal("3000"));if(sales>=40)return rules.getOrDefault("LADDER_40",new BigDecimal("2500"));if(sales>=30)return rules.getOrDefault("LADDER_30",new BigDecimal("2000"));if(sales>=20)return rules.getOrDefault("LADDER_20",new BigDecimal("1200"));if(sales>=10)return rules.getOrDefault("LADDER_10",new BigDecimal("600"));return BigDecimal.ZERO;}
    private String careerLevel(long base){if(base>=1601)return "DIAMANTE";if(base>=801)return "OURO";if(base>=401)return "PRATA";return "BRONZE";}
    private String nextCareerMilestone(long base){if(base<200)return "200 placas: camisa gola polo";if(base<401)return "401 placas: categoria Prata";if(base<500)return "500 placas: prêmio tablet/celular e abertura de escritório ao atingir 500";if(base<801)return "801 placas: categoria Ouro";if(base<1500)return "1.500 placas: iPhone 15 256GB";if(base<1601)return "1.601 placas: categoria Diamante";if(base<2500)return "2.500 placas: Honda Titan 160cc 0km";if(base<4000)return "4.000 placas: Fiat Mobi 0km";if(base<6000)return "6.000 placas: R$ 60.000 para troca de veículo";if(base<8000)return "8.000 placas: R$ 150.000 no Pix";if(base<10000)return "10.000 placas: R$ 200.000 no Pix";return "Meta máxima do guia atingida";}

    private YearMonth firstFinancialMonth(UUID consultantId){
        OffsetDateTime dt=jdbc.query("select min(paid_at) from financial_charges where consultant_id=? and status='RECEIVED'",rs->rs.next()?rs.getObject(1,OffsetDateTime.class):null,consultantId);
        return dt==null?null:YearMonth.from(dt);
    }
    private Map<String,BigDecimal> ruleValues(){Map<String,BigDecimal> m=new HashMap<>();jdbc.query("select rule_key,numeric_value from consultant_commission_rules where enabled=true",rs->{m.put(rs.getString(1),rs.getBigDecimal(2));});return m;}
    private long longValue(String sql,Object...args){Long v=jdbc.query(sql,rs->rs.next()?rs.getLong(1):0L,args);return v==null?0L:v;}
    private BigDecimal decimalValue(String sql,Object...args){BigDecimal v=jdbc.query(sql,rs->rs.next()?rs.getBigDecimal(1):BigDecimal.ZERO,args);return money(v);}
    private BigDecimal money(BigDecimal v){return (v==null?BigDecimal.ZERO:v).setScale(2,RoundingMode.HALF_UP);}
    private record BaseStats(long paidNewSales,long activePayingBase,BigDecimal activeBaseRevenue,BigDecimal ticket,long activeTotal,long motorcycleSales,long lightSales,long utilityTruckSales){}
}
