package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "promotional_motorcycle_prices")
public class PromotionalMotorcyclePrice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tier_code", nullable = false, unique = true, length = 40)
    private String tierCode;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(name = "min_cc", nullable = false)
    private Integer minCc;

    @Column(name = "max_cc", nullable = false)
    private Integer maxCc;

    @Column(name = "min_fipe", nullable = false, precision = 14, scale = 2)
    private BigDecimal minFipe;

    @Column(name = "max_fipe", precision = 14, scale = 2)
    private BigDecimal maxFipe;

    @Column(name = "monthly_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    protected PromotionalMotorcyclePrice() {
    }

    public Long getId() { return id; }
    public String getTierCode() { return tierCode; }
    public String getLabel() { return label; }
    public Integer getMinCc() { return minCc; }
    public Integer getMaxCc() { return maxCc; }
    public BigDecimal getMinFipe() { return minFipe; }
    public BigDecimal getMaxFipe() { return maxFipe; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public Integer getSortOrder() { return sortOrder; }

    public void updateConfiguration(
            String label, BigDecimal minFipe, BigDecimal maxFipe,
            Integer minCc, Integer maxCc, BigDecimal monthlyPrice
    ) {
        String normalizedLabel = label == null ? "" : label.trim();
        if (normalizedLabel.isBlank()) {
            throw new IllegalArgumentException("Informe o nome da faixa promocional.");
        }
        if (minCc == null || maxCc == null || minCc < 1 || maxCc < minCc) {
            throw new IllegalArgumentException("Intervalo de cilindradas inválido.");
        }
        BigDecimal normalizedMinFipe = minFipe == null ? BigDecimal.ZERO : minFipe;
        if (normalizedMinFipe.signum() < 0) {
            throw new IllegalArgumentException("Valor FIPE mínimo inválido.");
        }
        if (maxFipe != null && (maxFipe.signum() <= 0 || maxFipe.compareTo(normalizedMinFipe) < 0)) {
            throw new IllegalArgumentException("O FIPE máximo deve ser maior que zero e maior ou igual ao FIPE mínimo.");
        }
        if (monthlyPrice == null || monthlyPrice.signum() < 0) {
            throw new IllegalArgumentException("Valor mensal promocional inválido.");
        }

        this.label = normalizedLabel;
        this.minFipe = normalizedMinFipe;
        this.maxFipe = maxFipe;
        this.minCc = minCc;
        this.maxCc = maxCc;
        this.monthlyPrice = monthlyPrice;
    }

    public boolean matches(BigDecimal fipeValue, Integer motorcycleCc) {
        if (fipeValue == null || motorcycleCc == null) return false;
        if (motorcycleCc < minCc || motorcycleCc > maxCc) return false;
        if (minFipe != null && fipeValue.compareTo(minFipe) < 0) return false;
        return maxFipe == null || fipeValue.compareTo(maxFipe) <= 0;
    }
}
