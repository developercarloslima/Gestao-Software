package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * Cópia imutável de uma cobertura no momento em que a cotação é emitida.
 * Mantém o PDF histórico independente de futuras edições ou exclusões do plano.
 */
@Entity
@Table(
        name = "quotation_coverage_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_quote_coverage_snapshot",
                columnNames = {"quotation_id", "coverage_code"}
        )
)
public class QuotationCoverageSnapshot {
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

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage_status", nullable = false, length = 20)
    private CoverageStatus coverageStatus;

    @Column(length = 240)
    private String detail;

    @Column(name = "monthly_price", precision = 14, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "final_selected")
    private Boolean finalSelected;

    protected QuotationCoverageSnapshot() {}

    public static QuotationCoverageSnapshot create(
            Quotation quotation,
            String coverageCode,
            String coverageName,
            CoverageStatus coverageStatus,
            String detail,
            BigDecimal monthlyPrice,
            Integer sortOrder
    ) {
        if (quotation == null) throw new IllegalArgumentException("Cotação inválida.");
        if (coverageCode == null || coverageCode.isBlank()) throw new IllegalArgumentException("Código da cobertura inválido.");
        if (coverageName == null || coverageName.isBlank()) throw new IllegalArgumentException("Nome da cobertura inválido.");
        if (coverageStatus == null) throw new IllegalArgumentException("Classificação da cobertura inválida.");

        QuotationCoverageSnapshot snapshot = new QuotationCoverageSnapshot();
        snapshot.quotation = quotation;
        snapshot.coverageCode = coverageCode.trim();
        snapshot.coverageName = coverageName.trim();
        snapshot.coverageStatus = coverageStatus;
        snapshot.detail = detail == null || detail.isBlank() ? null : detail.trim();
        snapshot.monthlyPrice = monthlyPrice;
        snapshot.sortOrder = sortOrder == null ? 100 : Math.max(0, sortOrder);
        snapshot.finalSelected = coverageStatus == CoverageStatus.INCLUDED;
        return snapshot;
    }

    public Long getId() { return id; }
    public String getCoverageCode() { return coverageCode; }
    public String getCoverageName() { return coverageName; }
    public CoverageStatus getCoverageStatus() { return coverageStatus; }
    public String getDetail() { return detail; }
    public BigDecimal getMonthlyPrice() { return monthlyPrice; }
    public Integer getSortOrder() { return sortOrder; }
    public boolean isFinalSelected() { return finalSelected != null ? finalSelected : coverageStatus == CoverageStatus.INCLUDED; }
    public Boolean getFinalSelected() { return finalSelected; }

    public void setFinalSelected(boolean selected) {
        this.finalSelected = selected;
    }

    public void updateFinalDetail(String detail) {
        this.detail = detail == null || detail.isBlank() ? null : detail.trim();
    }
}
