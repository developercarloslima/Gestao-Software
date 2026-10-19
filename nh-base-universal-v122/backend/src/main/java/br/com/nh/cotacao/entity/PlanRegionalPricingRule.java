package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "plan_regional_pricing_rules", uniqueConstraints = @UniqueConstraint(name = "uq_plan_regional_pricing_rule", columnNames = {"plan_id", "pricing_region"}))
public class PlanRegionalPricingRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_region", nullable = false, length = 30)
    private PricingRegion pricingRegion;

    @Column(name = "extra_above", precision = 14, scale = 2)
    private BigDecimal extraAbove;

    @Column(name = "extra_step", precision = 14, scale = 2)
    private BigDecimal extraStep;

    @Column(name = "extra_increment", precision = 14, scale = 2)
    private BigDecimal extraIncrement;

    @Column(name = "extra_base_price", precision = 14, scale = 2)
    private BigDecimal extraBasePrice;

    protected PlanRegionalPricingRule() {}

    public Long getId() { return id; }
    public Plan getPlan() { return plan; }
    public PricingRegion getPricingRegion() { return pricingRegion; }
    public BigDecimal getExtraAbove() { return extraAbove; }
    public BigDecimal getExtraStep() { return extraStep; }
    public BigDecimal getExtraIncrement() { return extraIncrement; }
    public BigDecimal getExtraBasePrice() { return extraBasePrice; }
}
