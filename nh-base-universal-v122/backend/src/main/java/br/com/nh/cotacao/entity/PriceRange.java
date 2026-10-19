package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "price_ranges")
public class PriceRange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "min_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal minValue;

    @Column(name = "max_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal maxValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_region", nullable = false, length = 30)
    private PricingRegion pricingRegion;

    @Column(name = "monthly_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    protected PriceRange() {
    }

    public static PriceRange create(Plan plan, PricingRegion pricingRegion, BigDecimal minValue, BigDecimal maxValue, BigDecimal monthlyPrice) {
        PriceRange range = new PriceRange();
        range.plan = plan;
        range.pricingRegion = pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
        range.update(minValue, maxValue, monthlyPrice);
        return range;
    }

    public static PriceRange create(Plan plan, BigDecimal minValue, BigDecimal maxValue, BigDecimal monthlyPrice) {
        return create(plan, PricingRegion.NORTHEAST, minValue, maxValue, monthlyPrice);
    }

    public Long getId() { return id; }
    public Plan getPlan() { return plan; }
    public BigDecimal getMinValue() { return minValue; }
    public BigDecimal getMaxValue() { return maxValue; }
    public PricingRegion getPricingRegion() { return pricingRegion; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public void updateMonthlyPrice(BigDecimal monthlyPrice) {
        update(this.minValue, this.maxValue, monthlyPrice);
    }

    public void update(PricingRegion pricingRegion, BigDecimal minValue, BigDecimal maxValue, BigDecimal monthlyPrice) {
        this.pricingRegion = pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
        update(minValue, maxValue, monthlyPrice);
    }

    public void update(BigDecimal minValue, BigDecimal maxValue, BigDecimal monthlyPrice) {
        if (minValue == null || minValue.signum() < 0) throw new IllegalArgumentException("Valor FIPE mínimo inválido.");
        if (maxValue == null || maxValue.compareTo(minValue) < 0) throw new IllegalArgumentException("Valor FIPE máximo inválido.");
        if (monthlyPrice == null || monthlyPrice.signum() < 0) throw new IllegalArgumentException("Valor mensal inválido.");
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.monthlyPrice = monthlyPrice;
    }
}
