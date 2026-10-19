package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "plan_coverages")
public class PlanCoverage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coverage_id", nullable = false)
    private Coverage coverage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CoverageStatus status;

    @Column(length = 240)
    private String detail;

    @Column(name = "monthly_price", precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "damage_condition", length = 32)
    private DamageCondition damageCondition;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_condition", length = 32)
    private EventCondition eventCondition;

    @Column(name = "towing_radius_km")
    private Integer towingRadiusKm;

    protected PlanCoverage() {
    }

    public static PlanCoverage create(
            Plan plan,
            Coverage coverage,
            CoverageStatus status,
            String detail,
            BigDecimal monthlyPrice,
            Integer sortOrder
    ) {
        PlanCoverage item = new PlanCoverage();
        item.plan = plan;
        item.coverage = coverage;
        item.update(status, detail, monthlyPrice, sortOrder, null, null, null);
        return item;
    }

    public static PlanCoverage create(
            Plan plan,
            Coverage coverage,
            CoverageStatus status,
            String detail,
            BigDecimal monthlyPrice,
            Integer sortOrder,
            DamageCondition damageCondition,
            EventCondition eventCondition,
            Integer towingRadiusKm
    ) {
        PlanCoverage item = new PlanCoverage();
        item.plan = plan;
        item.coverage = coverage;
        item.update(status, detail, monthlyPrice, sortOrder, damageCondition, eventCondition, towingRadiusKm);
        return item;
    }

    public void replacePlan(Plan plan) {
        if (plan == null) throw new IllegalArgumentException("Plano inválido.");
        this.plan = plan;
    }

    public void replaceCoverage(Coverage coverage) {
        if (coverage == null) throw new IllegalArgumentException("Cobertura inválida.");
        this.coverage = coverage;
    }

    public void update(CoverageStatus status, String detail, BigDecimal monthlyPrice, Integer sortOrder) {
        update(status, detail, monthlyPrice, sortOrder, this.damageCondition, this.eventCondition, this.towingRadiusKm);
    }

    public void update(
            CoverageStatus status, String detail, BigDecimal monthlyPrice, Integer sortOrder,
            DamageCondition damageCondition, EventCondition eventCondition, Integer towingRadiusKm
    ) {
        if (status == null) {
            throw new IllegalArgumentException("Defina se o benefício é integrado ao plano, não incluído ou adicional pago.");
        }
        if (sortOrder == null || sortOrder < 0) {
            throw new IllegalArgumentException("A ordem de exibição deve ser zero ou maior.");
        }
        if (monthlyPrice != null && monthlyPrice.signum() < 0) {
            throw new IllegalArgumentException("Valor mensal inválido.");
        }
        if (status == CoverageStatus.OPTIONAL && monthlyPrice == null) {
            throw new IllegalArgumentException("Adicionais pagos precisam de um valor mensal.");
        }
        if (towingRadiusKm != null && towingRadiusKm < 0) {
            throw new IllegalArgumentException("O raio/limite de reboque não pode ser negativo.");
        }

        this.status = status;
        this.detail = detail == null || detail.isBlank() ? null : detail.trim();
        this.monthlyPrice = status == CoverageStatus.OPTIONAL ? monthlyPrice : null;
        this.sortOrder = sortOrder;
        this.damageCondition = damageCondition;
        this.eventCondition = eventCondition;
        this.towingRadiusKm = towingRadiusKm;
    }

    public Long getId() { return id; }
    public Plan getPlan() { return plan; }
    public Coverage getCoverage() { return coverage; }
    public CoverageStatus getStatus() { return status; }
    public String getDetail() { return detail; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public Integer getSortOrder() { return sortOrder; }
    public DamageCondition getDamageCondition() { return damageCondition; }
    public EventCondition getEventCondition() { return eventCondition; }
    public Integer getTowingRadiusKm() { return towingRadiusKm; }

    public String getEffectiveDetail() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (detail != null && !detail.isBlank()) parts.add(detail.trim());
        if (damageCondition != null) {
            parts.add(switch (damageCondition) {
                case ANY_DAMAGE -> "Válido para dano total ou parcial";
                case TOTAL_ONLY -> "Válido somente para dano total";
                case PARTIAL_ONLY -> "Válido somente para dano parcial";
            });
        }
        if (eventCondition != null) {
            parts.add(switch (eventCondition) {
                case ANY_ORIGIN -> "Válido para evento de qualquer origem";
                case COLLISION_DERIVED -> "Válido somente quando o evento for derivado de colisão";
            });
        }
        if (towingRadiusKm != null) {
            parts.add("Raio/limite de reboque: " + towingRadiusKm + " km");
        }
        return parts.isEmpty() ? null : String.join(" • ", parts);
    }

    public void updateMonthlyPrice(BigDecimal monthlyPrice) {
        if (status != CoverageStatus.OPTIONAL) {
            throw new IllegalArgumentException("Somente adicionais pagos possuem valor mensal editável.");
        }
        if (monthlyPrice == null || monthlyPrice.signum() < 0) {
            throw new IllegalArgumentException("Valor mensal inválido.");
        }
        this.monthlyPrice = monthlyPrice;
    }
}
