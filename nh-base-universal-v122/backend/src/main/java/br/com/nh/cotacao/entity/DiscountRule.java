package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "discount_rules")
public class DiscountRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, unique = true)
    private Integer percent;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "min_vehicle_age_years")
    private Integer minVehicleAgeYears;

    @Column(name = "max_vehicle_age_years")
    private Integer maxVehicleAgeYears;

    @Column(name = "min_fipe", precision = 14, scale = 2)
    private BigDecimal minFipe;

    @Column(name = "max_fipe", precision = 14, scale = 2)
    private BigDecimal maxFipe;

    @Column(name = "requires_rear_window_perforation", nullable = false)
    private Boolean requiresRearWindowPerforation;

    @Column(name = "apply_to_base", nullable = false)
    private Boolean applyToBase;

    @Column(name = "apply_to_mandatory_fee", nullable = false)
    private Boolean applyToMandatoryFee;

    @Column(name = "apply_to_optionals", nullable = false)
    private Boolean applyToOptionals;

    @Column(length = 600)
    private String notes;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "discount_rule_plans",
            joinColumns = @JoinColumn(name = "discount_rule_id"),
            inverseJoinColumns = @JoinColumn(name = "plan_id")
    )
    @OrderBy("displayOrder ASC, id ASC")
    private Set<Plan> plans = new LinkedHashSet<>();

    @OneToMany(mappedBy = "discountRule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<DiscountBenefitRule> benefitRules = new ArrayList<>();

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DiscountRule() {}

    public static DiscountRule create(
            String name, Integer percent, Boolean active, Integer displayOrder,
            Integer minVehicleAgeYears, Integer maxVehicleAgeYears,
            BigDecimal minFipe, BigDecimal maxFipe,
            Boolean requiresRearWindowPerforation,
            Boolean applyToBase, Boolean applyToMandatoryFee, Boolean applyToOptionals,
            String notes
    ) {
        DiscountRule rule = new DiscountRule();
        rule.update(name, percent, active, displayOrder, minVehicleAgeYears, maxVehicleAgeYears,
                minFipe, maxFipe, requiresRearWindowPerforation,
                applyToBase, applyToMandatoryFee, applyToOptionals, notes);
        return rule;
    }

    public void update(
            String name, Integer percent, Boolean active, Integer displayOrder,
            Integer minVehicleAgeYears, Integer maxVehicleAgeYears,
            BigDecimal minFipe, BigDecimal maxFipe,
            Boolean requiresRearWindowPerforation,
            Boolean applyToBase, Boolean applyToMandatoryFee, Boolean applyToOptionals,
            String notes
    ) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Informe o nome do desconto.");
        if (percent == null || percent < 1 || percent > 100) throw new IllegalArgumentException("O percentual deve ficar entre 1% e 100%.");
        if (displayOrder == null || displayOrder < 0) throw new IllegalArgumentException("A ordem deve ser zero ou maior.");
        if (minVehicleAgeYears != null && minVehicleAgeYears < 0) throw new IllegalArgumentException("A idade mínima do veículo não pode ser negativa.");
        if (maxVehicleAgeYears != null && maxVehicleAgeYears < 0) throw new IllegalArgumentException("A idade máxima do veículo não pode ser negativa.");
        if (minVehicleAgeYears != null && maxVehicleAgeYears != null && maxVehicleAgeYears < minVehicleAgeYears) {
            throw new IllegalArgumentException("A idade máxima do veículo não pode ser menor que a idade mínima.");
        }
        if (minFipe != null && minFipe.signum() < 0) throw new IllegalArgumentException("A FIPE mínima não pode ser negativa.");
        if (maxFipe != null && maxFipe.signum() < 0) throw new IllegalArgumentException("A FIPE máxima não pode ser negativa.");
        if (minFipe != null && maxFipe != null && maxFipe.compareTo(minFipe) < 0) {
            throw new IllegalArgumentException("A FIPE máxima não pode ser menor que a FIPE mínima.");
        }
        if (!Boolean.TRUE.equals(applyToBase) && !Boolean.TRUE.equals(applyToMandatoryFee) && !Boolean.TRUE.equals(applyToOptionals)) {
            throw new IllegalArgumentException("Escolha pelo menos um componente do preço para receber o desconto.");
        }
        this.name = name.trim();
        this.percent = percent;
        this.active = active == null || active;
        this.displayOrder = displayOrder;
        this.minVehicleAgeYears = minVehicleAgeYears;
        this.maxVehicleAgeYears = maxVehicleAgeYears;
        this.minFipe = minFipe;
        this.maxFipe = maxFipe;
        this.requiresRearWindowPerforation = Boolean.TRUE.equals(requiresRearWindowPerforation);
        this.applyToBase = Boolean.TRUE.equals(applyToBase);
        this.applyToMandatoryFee = Boolean.TRUE.equals(applyToMandatoryFee);
        this.applyToOptionals = Boolean.TRUE.equals(applyToOptionals);
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
        this.updatedAt = OffsetDateTime.now();
    }

    public void replacePlans(List<Plan> newPlans) {
        plans.clear();
        if (newPlans != null) plans.addAll(newPlans);
    }

    public void replaceBenefitRules(List<DiscountBenefitRule> newRules) {
        benefitRules.clear();
        if (newRules != null) benefitRules.addAll(newRules);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Integer getPercent() { return percent; }
    public Boolean getActive() { return active; }
    public Integer getDisplayOrder() { return displayOrder; }
    public Integer getMinVehicleAgeYears() { return minVehicleAgeYears; }
    public Integer getMaxVehicleAgeYears() { return maxVehicleAgeYears; }
    public BigDecimal getMinFipe() { return minFipe; }
    public BigDecimal getMaxFipe() { return maxFipe; }
    public Boolean getRequiresRearWindowPerforation() { return requiresRearWindowPerforation; }
    public Boolean getApplyToBase() { return applyToBase; }
    public Boolean getApplyToMandatoryFee() { return applyToMandatoryFee; }
    public Boolean getApplyToOptionals() { return applyToOptionals; }
    public String getNotes() { return notes; }
    public Set<Plan> getPlans() { return plans; }
    public List<DiscountBenefitRule> getBenefitRules() { return benefitRules; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
