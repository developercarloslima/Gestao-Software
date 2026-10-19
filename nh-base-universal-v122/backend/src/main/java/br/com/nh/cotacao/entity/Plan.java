package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "plans")
public class Plan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 180)
    private String subtitle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private VehicleCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(name = "motorcycle_origin", length = 20)
    private MotorcycleOrigin motorcycleOrigin;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "extra_above", precision = 14, scale = 2)
    private BigDecimal extraAbove;

    @Column(name = "extra_step", precision = 14, scale = 2)
    private BigDecimal extraStep;

    @Column(name = "extra_increment", precision = 14, scale = 2)
    private BigDecimal extraIncrement;

    @Column(name = "extra_base_price", precision = 14, scale = 2)
    private BigDecimal extraBasePrice;

    @Column(name = "tracker_required_above", precision = 14, scale = 2)
    private BigDecimal trackerRequiredAbove;

    @Column(name = "tracker_installation_fee", precision = 14, scale = 2)
    private BigDecimal trackerInstallationFee;

    @Column(name = "tracker_monthly_fee", precision = 14, scale = 2)
    private BigDecimal trackerMonthlyFee;

    @OneToMany(mappedBy = "plan", fetch = FetchType.LAZY)
    @OrderBy("sortOrder ASC")
    private List<PlanCoverage> coverages = new ArrayList<>();

    protected Plan() {
    }

    public static Plan create(
            String code,
            String name,
            String subtitle,
            VehicleCategory category,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            Integer displayOrder,
            Boolean active,
            BigDecimal extraAbove,
            BigDecimal extraStep,
            BigDecimal extraIncrement,
            BigDecimal extraBasePrice,
            BigDecimal trackerRequiredAbove,
            BigDecimal trackerInstallationFee,
            BigDecimal trackerMonthlyFee
    ) {
        Plan plan = new Plan();
        plan.updateAdmin(
                code, name, subtitle, category, region, motorcycleOrigin, displayOrder, active,
                extraAbove, extraStep, extraIncrement, extraBasePrice,
                trackerRequiredAbove, trackerInstallationFee, trackerMonthlyFee
        );
        return plan;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSubtitle() { return subtitle; }
    public VehicleCategory getCategory() { return category; }
    public Region getRegion() { return region; }
    public MotorcycleOrigin getMotorcycleOrigin() { return motorcycleOrigin; }
    public Integer getDisplayOrder() { return displayOrder; }
    public Boolean getActive() { return active; }
    public BigDecimal getExtraAbove() { return extraAbove; }
    public BigDecimal getExtraStep() { return extraStep; }
    public BigDecimal getExtraIncrement() { return extraIncrement; }
    public BigDecimal getExtraBasePrice() { return extraBasePrice; }
    public BigDecimal getTrackerRequiredAbove() { return trackerRequiredAbove; }
    public BigDecimal getTrackerInstallationFee() { return trackerInstallationFee; }
    public BigDecimal getTrackerMonthlyFee() { return trackerMonthlyFee; }
    public List<PlanCoverage> getCoverages() { return coverages; }

    public void updateAdmin(
            String code,
            String name,
            String subtitle,
            VehicleCategory category,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            Integer displayOrder,
            Boolean active,
            BigDecimal extraAbove,
            BigDecimal extraStep,
            BigDecimal extraIncrement,
            BigDecimal extraBasePrice,
            BigDecimal trackerRequiredAbove,
            BigDecimal trackerInstallationFee,
            BigDecimal trackerMonthlyFee
    ) {
        this.code = requireText(code, "Código do plano");
        this.name = requireText(name, "Nome do plano");
        this.subtitle = cleanOptional(subtitle);
        if (category == null) throw new IllegalArgumentException("Categoria do plano é obrigatória.");
        if (region != null && region != Region.NATIONAL) {
            throw new IllegalArgumentException("Todos os planos e pacotes devem possuir abrangência nacional.");
        }
        boolean motorcycle = category.getCode() != null && category.getCode().startsWith("MOTORCYCLE");
        boolean promotionalMotorcycle = "MOTORCYCLE_PROMO_2026".equals(category.getCode());
        if (motorcycle && !promotionalMotorcycle && motorcycleOrigin == null) {
            throw new IllegalArgumentException("Informe a origem da moto para aplicar a tabela correta.");
        }
        if ((!motorcycle || promotionalMotorcycle) && motorcycleOrigin != null) {
            throw new IllegalArgumentException(promotionalMotorcycle
                    ? "A tabela promocional não utiliza origem da moto."
                    : "A origem da moto só pode ser usada em categorias de motocicletas.");
        }
        if (displayOrder == null || displayOrder < 0) {
            throw new IllegalArgumentException("A ordem do plano deve ser zero ou maior.");
        }
        this.category = category;
        this.region = Region.NATIONAL;
        this.motorcycleOrigin = motorcycle && !promotionalMotorcycle ? motorcycleOrigin : null;
        this.displayOrder = displayOrder;
        this.active = active == null || active;

        validateNonNegative(extraAbove, "Valor FIPE de início do cálculo adicional");
        validatePositive(extraStep, "Intervalo FIPE do cálculo adicional");
        validateNonNegative(extraIncrement, "Acréscimo mensal por intervalo");
        validateNonNegative(extraBasePrice, "Mensalidade base do cálculo adicional");
        boolean anyExtra = extraAbove != null || extraStep != null || extraIncrement != null || extraBasePrice != null;
        boolean allExtra = extraAbove != null && extraStep != null && extraIncrement != null && extraBasePrice != null;
        if (anyExtra && !allExtra) {
            throw new IllegalArgumentException("Para usar a regra de valores acima da tabela, preencha os quatro campos do cálculo adicional.");
        }
        this.extraAbove = extraAbove;
        this.extraStep = extraStep;
        this.extraIncrement = extraIncrement;
        this.extraBasePrice = extraBasePrice;

        validateNonNegative(trackerRequiredAbove, "Valor FIPE para rastreador obrigatório");
        validateNonNegative(trackerInstallationFee, "Taxa de instalação do rastreador");
        validateNonNegative(trackerMonthlyFee, "Mensalidade do rastreador");
        boolean anyTracker = trackerRequiredAbove != null || trackerInstallationFee != null || trackerMonthlyFee != null;
        boolean allTracker = trackerRequiredAbove != null && trackerInstallationFee != null && trackerMonthlyFee != null;
        if (anyTracker && !allTracker) {
            throw new IllegalArgumentException("Para tornar o rastreador obrigatório, preencha o limite FIPE, a instalação e a mensalidade.");
        }
        this.trackerRequiredAbove = trackerRequiredAbove;
        this.trackerInstallationFee = trackerInstallationFee;
        this.trackerMonthlyFee = trackerMonthlyFee;
    }

    private static void validateNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(field + " não pode ser negativo.");
        }
    }

    private static void validatePositive(BigDecimal value, String field) {
        if (value != null && value.signum() <= 0) {
            throw new IllegalArgumentException(field + " deve ser maior que zero.");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " é obrigatório.");
        return value.trim();
    }

    private static String cleanOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
