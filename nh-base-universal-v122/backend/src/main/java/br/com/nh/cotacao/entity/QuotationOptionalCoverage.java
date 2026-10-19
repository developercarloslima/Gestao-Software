package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "quotation_optional_coverages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_quotation_optional_coverage",
                columnNames = {"quotation_id", "coverage_code"}
        )
)
public class QuotationOptionalCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @Column(name = "coverage_code", nullable = false, length = 80)
    private String coverageCode;

    @Column(name = "coverage_name", nullable = false, length = 180)
    private String coverageName;

    @Column(length = 240)
    private String detail;

    @Column(name = "monthly_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    protected QuotationOptionalCoverage() {
    }

    static QuotationOptionalCoverage create(
            Quotation quotation,
            String coverageCode,
            String coverageName,
            String detail,
            BigDecimal monthlyPrice
    ) {
        QuotationOptionalCoverage optional = new QuotationOptionalCoverage();
        optional.quotation = quotation;
        optional.coverageCode = coverageCode;
        optional.coverageName = coverageName;
        optional.detail = detail;
        optional.monthlyPrice = monthlyPrice;
        return optional;
    }

    public Long getId() { return id; }
    public Quotation getQuotation() { return quotation; }
    public String getCoverageCode() { return coverageCode; }
    public String getCoverageName() { return coverageName; }
    public String getDetail() { return detail; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }

    void updateFromCatalog(String coverageName, String detail, BigDecimal monthlyPrice) {
        if (coverageName == null || coverageName.isBlank()) {
            throw new IllegalArgumentException("Nome do serviço adicional inválido.");
        }
        if (monthlyPrice == null || monthlyPrice.signum() < 0) {
            throw new IllegalArgumentException("Valor mensal do serviço adicional inválido.");
        }
        this.coverageName = coverageName.trim();
        this.detail = detail == null || detail.isBlank() ? null : detail.trim();
        this.monthlyPrice = monthlyPrice.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
