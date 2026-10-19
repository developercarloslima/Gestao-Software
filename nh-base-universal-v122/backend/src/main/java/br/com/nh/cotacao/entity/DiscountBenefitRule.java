package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "discount_rule_benefits")
public class DiscountBenefitRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discount_rule_id", nullable = false)
    private DiscountRule discountRule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coverage_id", nullable = false)
    private Coverage coverage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DiscountBenefitAction action;

    @Column(name = "detail_override", length = 240)
    private String detailOverride;

    @Column(name = "amount_override", precision = 14, scale = 2)
    private BigDecimal amountOverride;

    protected DiscountBenefitRule() {}

    public static DiscountBenefitRule create(
            DiscountRule discountRule, Coverage coverage, DiscountBenefitAction action,
            String detailOverride, BigDecimal amountOverride
    ) {
        if (discountRule == null) throw new IllegalArgumentException("Desconto inválido.");
        if (coverage == null) throw new IllegalArgumentException("Benefício/cobertura inválido.");
        if (action == null) throw new IllegalArgumentException("Informe a ação do desconto sobre o benefício.");
        if (amountOverride != null && amountOverride.signum() < 0) throw new IllegalArgumentException("O valor/limite substituto não pode ser negativo.");
        DiscountBenefitRule rule = new DiscountBenefitRule();
        rule.discountRule = discountRule;
        rule.coverage = coverage;
        rule.action = action;
        rule.detailOverride = detailOverride == null || detailOverride.isBlank() ? null : detailOverride.trim();
        rule.amountOverride = amountOverride;
        return rule;
    }

    public Long getId() { return id; }
    public DiscountRule getDiscountRule() { return discountRule; }
    public Coverage getCoverage() { return coverage; }
    public DiscountBenefitAction getAction() { return action; }
    public String getDetailOverride() { return detailOverride; }
    public BigDecimal getAmountOverride() { return amountOverride; }
}
