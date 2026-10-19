package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "coverage_rules")
public class CoverageRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coverage_id", nullable = false)
    private Coverage coverage;

    @Column(name = "category_code", length = 50)
    private String categoryCode;

    @Column(name = "min_fipe", nullable = false, precision = 14, scale = 2)
    private BigDecimal minFipe;

    @Column(name = "max_fipe", precision = 14, scale = 2)
    private BigDecimal maxFipe;

    @Column(name = "normal_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal normalAmount;

    @Column(name = "discounted_amount", precision = 14, scale = 2)
    private BigDecimal discountedAmount;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    protected CoverageRule() {}

    public static CoverageRule create(
            Coverage coverage,
            String categoryCode,
            BigDecimal minFipe,
            BigDecimal maxFipe,
            BigDecimal normalAmount,
            BigDecimal discountedAmount,
            Integer sortOrder
    ) {
        CoverageRule rule = new CoverageRule();
        rule.coverage = coverage;
        rule.update(categoryCode, minFipe, maxFipe, normalAmount, discountedAmount, sortOrder);
        return rule;
    }

    public void update(
            String categoryCode,
            BigDecimal minFipe,
            BigDecimal maxFipe,
            BigDecimal normalAmount,
            BigDecimal discountedAmount,
            Integer sortOrder
    ) {
        BigDecimal min = minFipe == null ? BigDecimal.ZERO : minFipe;
        if (min.signum() < 0) throw new IllegalArgumentException("FIPE mínimo da regra inválido.");
        if (maxFipe != null && maxFipe.compareTo(min) < 0) {
            throw new IllegalArgumentException("FIPE máximo deve ser maior ou igual ao FIPE mínimo.");
        }
        if (normalAmount == null || normalAmount.signum() < 0) {
            throw new IllegalArgumentException("Informe o valor normal da cobertura.");
        }
        if (discountedAmount != null && discountedAmount.signum() < 0) {
            throw new IllegalArgumentException("Valor da cobertura com desconto inválido.");
        }
        if (sortOrder == null || sortOrder < 0) throw new IllegalArgumentException("Ordem da regra inválida.");

        this.categoryCode = categoryCode == null || categoryCode.isBlank() ? null : categoryCode.trim().toUpperCase();
        this.minFipe = min;
        this.maxFipe = maxFipe;
        this.normalAmount = normalAmount;
        this.discountedAmount = discountedAmount;
        this.sortOrder = sortOrder;
    }

    public boolean matches(String categoryCode, BigDecimal fipeValue) {
        if (fipeValue == null) return false;
        if (this.categoryCode != null && (categoryCode == null || !this.categoryCode.equalsIgnoreCase(categoryCode))) return false;
        if (fipeValue.compareTo(minFipe) < 0) return false;
        return maxFipe == null || fipeValue.compareTo(maxFipe) <= 0;
    }

    public Long getId() { return id; }
    public Coverage getCoverage() { return coverage; }
    public String getCategoryCode() { return categoryCode; }
    public BigDecimal getMinFipe() { return minFipe; }
    public BigDecimal getMaxFipe() { return maxFipe; }
    public BigDecimal getNormalAmount() { return normalAmount; }
    public BigDecimal getDiscountedAmount() { return discountedAmount; }
    public Integer getSortOrder() { return sortOrder; }
}
