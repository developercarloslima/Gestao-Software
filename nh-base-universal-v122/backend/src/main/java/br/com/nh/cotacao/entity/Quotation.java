package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "quotations")
public class Quotation {
    public static final String SELF_SERVICE_CONSULTANT_NAME = "Autoatendimento do cliente";

    @Id
    private UUID id;

    @Column(name = "quote_number", nullable = false, unique = true, length = 30)
    private String quoteNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private QuoteOrigin origin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consultant_id")
    private Consultant consultant;

    @Column(name = "consultant_name", nullable = false, length = 140)
    private String consultantName;

    @Column(name = "customer_name", nullable = false, length = 120)
    private String customerName;

    @Column(name = "customer_cpf", length = 11)
    private String customerCpf;

    @Column(length = 30)
    private String whatsapp;

    @Column(length = 10)
    private String plate;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(name = "manufacture_year", nullable = false)
    private Integer manufactureYear;

    @Column(name = "zero_km", nullable = false)
    private boolean zeroKm;

    @Column(name = "fipe_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal fipeValue;

    @Column(name = "vehicle_fipe_code", length = 24)
    private String vehicleFipeCode;
    @Column(name = "vehicle_fipe_reference", length = 70)
    private String vehicleFipeReference;
    @Column(name = "vehicle_fipe_source", length = 40)
    private String vehicleFipeSource;

    @Column(name = "auction_or_chassis_remarked")
    private Boolean auctionOrChassisRemarked;

    @Column(name = "indemnity_fipe_percent", nullable = false)
    private Integer indemnityFipePercent;

    @Column(name = "category_code", nullable = false, length = 50)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_region", nullable = false, length = 30)
    private PricingRegion pricingRegion;

    @Enumerated(EnumType.STRING)
    @Column(name = "motorcycle_origin", length = 30)
    private MotorcycleOrigin motorcycleOrigin;

    @Column(name = "motorcycle_cc")
    private Integer motorcycleCc;

    @Column(name = "observation", length = 1200)
    private String observation;

    @Column(name = "residence_cep", length = 8)
    private String residenceCep;

    @Column(name = "residence_address", length = 600)
    private String residenceAddress;

    @Column(name = "billing_due_day")
    private Integer billingDueDay;

    @Column(name = "first_billing_due_date")
    private LocalDate firstBillingDueDate;

    @Column(name = "selected_plan_code", nullable = false, length = 80)
    private String selectedPlanCode;

    @Column(name = "selected_plan_name", nullable = false, length = 120)
    private String selectedPlanName;

    @Column(name = "base_monthly_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseMonthlyValue;

    @Column(name = "monthly_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal monthlyValue;

    @Column(name = "pre_discount_monthly_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal preDiscountMonthlyValue;

    @Column(name = "discount_percent", nullable = false)
    private Integer discountPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "rear_window_branding", nullable = false, length = 40)
    private RearWindowBranding rearWindowBranding;

    @Column(name = "mandatory_monthly_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal mandatoryMonthlyFee;

    @Column(name = "one_time_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal oneTimeFee;

    @Column(name = "mandatory_fee_description", length = 240)
    private String mandatoryFeeDescription;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<QuotationOptionalCoverage> selectedOptionals = new ArrayList<>();

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC, id ASC")
    private Set<QuotationCoverageSnapshot> coverageSnapshots = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuoteStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "valid_until", nullable = false)
    private OffsetDateTime validUntil;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "admin_note", length = 1200)
    private String adminNote;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "drive_folder_id", length = 160)
    private String driveFolderId;

    @Column(name = "drive_folder_url", length = 500)
    private String driveFolderUrl;

    @Column(name = "drive_pdf_file_id", length = 160)
    private String drivePdfFileId;

    @Column(name = "drive_pdf_url", length = 500)
    private String drivePdfUrl;

    @Column(name = "inspection_completed_at")
    private OffsetDateTime inspectionCompletedAt;

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private Set<InspectionPhoto> inspectionPhotos = new LinkedHashSet<>();

    @PrePersist
    private void initializeTimestamps() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    private void touchUpdatedAt() {
        updatedAt = OffsetDateTime.now();
    }

    protected Quotation() {
    }

    public static Quotation createForConsultant(
            String quoteNumber,
            Consultant consultant,
            String customerName,
            String customerCpf,
            String whatsapp,
            String plate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            BigDecimal fipeValue,
            String categoryCode,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            String selectedPlanCode,
            String selectedPlanName,
            BigDecimal baseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription
    ) {
        Integer legacyCc = null;
        if ("MOTORCYCLE_UP_TO_300".equals(categoryCode)) legacyCc = Integer.valueOf(300);
        else if ("MOTORCYCLE_OVER_300".equals(categoryCode)) legacyCc = Integer.valueOf(301);
        return createForConsultant(
                quoteNumber, consultant, customerName, customerCpf, whatsapp, plate, model, manufactureYear, zeroKm,
                fipeValue, categoryCode, region, PricingRegion.NORTHEAST, motorcycleOrigin, legacyCc, null, selectedPlanCode, selectedPlanName,
                baseMonthlyValue, mandatoryMonthlyFee, oneTimeFee, mandatoryFeeDescription
        );
    }

    public static Quotation createForConsultant(
            String quoteNumber,
            Consultant consultant,
            String customerName,
            String customerCpf,
            String whatsapp,
            String plate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            BigDecimal fipeValue,
            String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            Integer motorcycleCc,
            String observation,
            String selectedPlanCode,
            String selectedPlanName,
            BigDecimal baseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription
    ) {
        if (consultant == null) throw new IllegalArgumentException("Informe o consultor responsável.");
        return createBase(
                quoteNumber,
                QuoteOrigin.CONSULTANT,
                consultant,
                consultant.getName(),
                customerName,
                customerCpf,
                whatsapp,
                plate,
                model,
                manufactureYear,
                zeroKm,
                fipeValue,
                categoryCode,
                region,
                pricingRegion,
                motorcycleOrigin,
                motorcycleCc,
                observation,
                selectedPlanCode,
                selectedPlanName,
                baseMonthlyValue,
                mandatoryMonthlyFee,
                oneTimeFee,
                mandatoryFeeDescription
        );
    }

    public static Quotation createSelfService(
            String quoteNumber,
            Consultant consultant,
            String customerName,
            String customerCpf,
            String whatsapp,
            String plate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            BigDecimal fipeValue,
            String categoryCode,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            String selectedPlanCode,
            String selectedPlanName,
            BigDecimal baseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription
    ) {
        Integer legacyCc = null;
        if ("MOTORCYCLE_UP_TO_300".equals(categoryCode)) legacyCc = Integer.valueOf(300);
        else if ("MOTORCYCLE_OVER_300".equals(categoryCode)) legacyCc = Integer.valueOf(301);
        return createSelfService(
                quoteNumber, consultant, customerName, customerCpf, whatsapp, plate, model, manufactureYear, zeroKm,
                fipeValue, categoryCode, region, PricingRegion.NORTHEAST, motorcycleOrigin, legacyCc, null, selectedPlanCode, selectedPlanName,
                baseMonthlyValue, mandatoryMonthlyFee, oneTimeFee, mandatoryFeeDescription
        );
    }

    public static Quotation createSelfService(
            String quoteNumber,
            Consultant consultant,
            String customerName,
            String customerCpf,
            String whatsapp,
            String plate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            BigDecimal fipeValue,
            String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            Integer motorcycleCc,
            String observation,
            String selectedPlanCode,
            String selectedPlanName,
            BigDecimal baseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription
    ) {
        return createBase(
                quoteNumber,
                QuoteOrigin.SELF_SERVICE,
                consultant,
                consultant == null ? SELF_SERVICE_CONSULTANT_NAME : consultant.getName(),
                customerName,
                customerCpf,
                whatsapp,
                plate,
                model,
                manufactureYear,
                zeroKm,
                fipeValue,
                categoryCode,
                region,
                pricingRegion,
                motorcycleOrigin,
                motorcycleCc,
                observation,
                selectedPlanCode,
                selectedPlanName,
                baseMonthlyValue,
                mandatoryMonthlyFee,
                oneTimeFee,
                mandatoryFeeDescription
        );
    }

    private static Quotation createBase(
            String quoteNumber,
            QuoteOrigin origin,
            Consultant consultant,
            String consultantName,
            String customerName,
            String customerCpf,
            String whatsapp,
            String plate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            BigDecimal fipeValue,
            String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            Integer motorcycleCc,
            String observation,
            String selectedPlanCode,
            String selectedPlanName,
            BigDecimal baseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription
    ) {
        Quotation q = new Quotation();
        q.id = UUID.randomUUID();
        q.quoteNumber = quoteNumber;
        q.origin = origin;
        q.consultant = consultant;
        q.consultantName = consultantName;
        q.customerName = cleanName(customerName);
        q.customerCpf = customerCpf == null ? null : customerCpf.replaceAll("\\D", "");
        q.whatsapp = whatsapp;
        q.plate = normalizePlate(plate, zeroKm);
        q.model = model.trim();
        q.manufactureYear = manufactureYear;
        q.zeroKm = zeroKm;
        q.fipeValue = fipeValue;
        q.auctionOrChassisRemarked = null;
        q.indemnityFipePercent = 100;
        q.categoryCode = categoryCode;
        q.region = Region.NATIONAL;
        q.pricingRegion = pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
        q.motorcycleOrigin = motorcycleOrigin;
        q.motorcycleCc = validateMotorcycleCc(categoryCode, motorcycleCc);
        q.observation = cleanObservation(observation);
        q.selectedPlanCode = selectedPlanCode;
        q.selectedPlanName = selectedPlanName;
        q.baseMonthlyValue = baseMonthlyValue;
        q.mandatoryMonthlyFee = mandatoryMonthlyFee == null ? BigDecimal.ZERO : mandatoryMonthlyFee;
        q.oneTimeFee = oneTimeFee == null ? BigDecimal.ZERO : oneTimeFee;
        q.mandatoryFeeDescription = mandatoryFeeDescription;
        q.preDiscountMonthlyValue = baseMonthlyValue.add(q.mandatoryMonthlyFee);
        q.discountPercent = 0;
        q.rearWindowBranding = RearWindowBranding.NOT_APPLICABLE;
        q.monthlyValue = q.preDiscountMonthlyValue;
        q.status = QuoteStatus.CREATED;
        q.createdAt = OffsetDateTime.now();
        q.validUntil = q.createdAt.plusDays(5);
        return q;
    }

    private static final java.util.Set<Integer> ALLOWED_BILLING_DUE_DAYS = java.util.Set.of(5, 10, 15, 20, 25, 30);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Maceio");


    public void configureResidenceAddress(String cep, String address) {
        // Compatível com antigas integrações de cotações que ainda não enviam CEP.
        if ((cep == null || cep.isBlank()) && (address == null || address.isBlank())) {
            this.residenceCep = null;
            this.residenceAddress = null;
            return;
        }
        if (cep == null || !cep.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("Informe um CEP válido com 8 dígitos.");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Informe o endereço residencial completo.");
        }
        String clean = address.trim().replaceAll("\\s+", " ");
        if (clean.length() > 600) {
            throw new IllegalArgumentException("O endereço residencial deve possuir até 600 caracteres.");
        }
        this.residenceCep = cep;
        this.residenceAddress = clean;
    }

    public void configureVehicleHistory(Boolean auctionOrChassisRemarked) {
        this.auctionOrChassisRemarked = auctionOrChassisRemarked;
        this.indemnityFipePercent = Boolean.TRUE.equals(auctionOrChassisRemarked) ? 70 : 100;
    }

    public void configureBillingDueDate(LocalDate requestedDate) {
        if (requestedDate == null) {
            throw new IllegalArgumentException("Escolha o vencimento das mensalidades.");
        }
        LocalDate quoteDate = createdAt.atZoneSameInstant(BUSINESS_ZONE).toLocalDate();
        LocalDate minimum = quoteDate.plusDays(30);
        LocalDate maximum = quoteDate.plusDays(40);
        if (requestedDate.isBefore(minimum) || requestedDate.isAfter(maximum)) {
            throw new IllegalArgumentException("O primeiro vencimento deve ficar entre 30 e 40 dias após a data da cotação.");
        }
        if (!ALLOWED_BILLING_DUE_DAYS.contains(requestedDate.getDayOfMonth())) {
            throw new IllegalArgumentException("Os vencimentos permitidos são nos dias 5, 10, 15, 20, 25 ou 30.");
        }
        this.billingDueDay = requestedDate.getDayOfMonth();
        this.firstBillingDueDate = requestedDate;
    }

    private static String cleanName(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private static Integer validateMotorcycleCc(String categoryCode, Integer motorcycleCc) {
        if (!"MOTORCYCLE_PROMO_2026".equals(categoryCode)) return null;
        if (motorcycleCc == null || motorcycleCc < 1 || motorcycleCc > 300) {
            throw new IllegalArgumentException("Selecione uma faixa da Tabela Promocional de Motocicletas.");
        }
        return motorcycleCc;
    }

    private static String cleanObservation(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        if (clean.length() > 1200) {
            throw new IllegalArgumentException("A observação da cotação deve possuir no máximo 1.200 caracteres.");
        }
        return clean;
    }


    private static String normalizePlate(String value, boolean zeroKm) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (zeroKm && normalized.isBlank()) return null;
        if (!normalized.matches("^[A-Z0-9]{7,10}$")) {
            throw new IllegalArgumentException(zeroKm
                    ? "Informe uma placa válida ou deixe o campo vazio para veículo 0 km."
                    : "Informe uma placa válida.");
        }
        return normalized;
    }

    /** Troca o plano ainda não aceito, descartando snapshots e adicionais antigos. */
    public void changePlanBeforeDigitalAcceptance(String code, String name) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Selecione um plano válido.");
        }
        this.selectedPlanCode = code.trim();
        this.selectedPlanName = name.trim();
        this.discountPercent = 0;
        this.rearWindowBranding = RearWindowBranding.NOT_APPLICABLE;
        this.selectedOptionals.clear();
        this.coverageSnapshots.clear();
    }

    public void addCoverageSnapshot(
            String coverageCode,
            String coverageName,
            CoverageStatus coverageStatus,
            String detail,
            BigDecimal monthlyPrice,
            Integer sortOrder
    ) {
        boolean duplicate = coverageSnapshots.stream()
                .anyMatch(item -> item.getCoverageCode().equals(coverageCode));
        if (duplicate) throw new IllegalArgumentException("A cobertura já foi registrada nesta cotação.");
        coverageSnapshots.add(QuotationCoverageSnapshot.create(
                this, coverageCode, coverageName, coverageStatus, detail, monthlyPrice, sortOrder
        ));
    }

    public void addOptional(
            String coverageCode,
            String coverageName,
            String detail,
            BigDecimal monthlyPrice
    ) {
        if (monthlyPrice == null || monthlyPrice.signum() < 0) {
            throw new IllegalArgumentException("O opcional precisa possuir um valor mensal válido.");
        }
        boolean duplicate = selectedOptionals.stream()
                .anyMatch(item -> item.getCoverageCode().equals(coverageCode));
        if (duplicate) {
            throw new IllegalArgumentException("O mesmo opcional não pode ser selecionado mais de uma vez.");
        }
        selectedOptionals.add(QuotationOptionalCoverage.create(
                this,
                coverageCode,
                coverageName,
                detail,
                monthlyPrice
        ));
        coverageSnapshots.stream()
                .filter(item -> item.getCoverageCode().equalsIgnoreCase(coverageCode))
                .findFirst()
                .ifPresent(item -> item.setFinalSelected(true));
        preDiscountMonthlyValue = preDiscountMonthlyValue.add(monthlyPrice);
        recalculateDiscountedMonthlyValue();
    }

    public void applyDiscount(Integer requestedPercent, RearWindowBranding requestedBranding) {
        int percent = requestedPercent == null ? 0 : requestedPercent;
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("O desconto deve ficar entre 0% e 100%.");
        }
        this.discountPercent = percent;
        this.rearWindowBranding = percent == 0
                ? RearWindowBranding.NOT_APPLICABLE
                : (requestedBranding == null ? RearWindowBranding.NOT_APPLICABLE : requestedBranding);
        recalculateDiscountedMonthlyValue();
    }

    /**
     * Consolida o resultado calculado pelo motor administrativo de descontos.
     * O percentual pode atingir somente alguns componentes (base, taxa ou
     * adicionais), portanto não é correto reconstruir sempre como subtotal * %.
     */
    public void applyDiscountPricing(
            Integer requestedPercent, RearWindowBranding requestedBranding, BigDecimal finalMonthlyValue
    ) {
        int percent = requestedPercent == null ? 0 : requestedPercent;
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("O desconto deve ficar entre 0% e 100%.");
        }
        BigDecimal finalValue = finalMonthlyValue == null ? null : finalMonthlyValue.setScale(2, RoundingMode.HALF_UP);
        if (finalValue == null || finalValue.signum() < 0) {
            throw new IllegalArgumentException("O valor mensal final calculado é inválido.");
        }
        BigDecimal subtotal = preDiscountMonthlyValue == null ? BigDecimal.ZERO : preDiscountMonthlyValue.setScale(2, RoundingMode.HALF_UP);
        if (finalValue.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("O desconto não pode aumentar a mensalidade final.");
        }
        this.discountPercent = percent;
        this.rearWindowBranding = percent == 0
                ? RearWindowBranding.NOT_APPLICABLE
                : (requestedBranding == null ? RearWindowBranding.NOT_APPLICABLE : requestedBranding);
        this.monthlyValue = finalValue;
    }

    private void recalculateDiscountedMonthlyValue() {
        BigDecimal base = baseMonthlyValue == null ? BigDecimal.ZERO : baseMonthlyValue;
        BigDecimal mandatory = mandatoryMonthlyFee == null ? BigDecimal.ZERO : mandatoryMonthlyFee;
        BigDecimal optionals = selectedOptionalMonthlyValue();
        int percent = discountPercent == null ? 0 : discountPercent;
        BigDecimal factor = BigDecimal.valueOf(100 - percent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        // Desconto somente na mensalidade-base. Taxas e adicionais entram integrais.
        this.monthlyValue = base.multiply(factor)
                .add(mandatory)
                .add(optionals)
                .setScale(2, RoundingMode.HALF_UP);
    }


    public void assignConsultant(Consultant consultant) {
        if (consultant == null) throw new IllegalArgumentException("Informe o consultor responsável.");
        this.consultant = consultant;
        this.consultantName = consultant.getName();
    }

    public void registerCustomerCpf(String cpf) {
        String digits = cpf == null ? "" : cpf.replaceAll("\\D", "");
        if (digits.length() != 11) throw new IllegalArgumentException("Informe um CPF válido.");
        this.customerCpf = digits;
    }

    /**
     * Atualiza somente dados cadastrais que não participam do cálculo da cotação.
     * Plano, categoria, FIPE, coberturas e valores permanecem imutáveis.
     */
    public void updateNonPricingData(String customerName, String customerCpf, String whatsapp, String plate, String model, Integer manufactureYear, boolean zeroKm) {
        updateNonPricingData(customerName, customerCpf, whatsapp, plate, model, manufactureYear, zeroKm, this.observation);
    }

    public void updateNonPricingData(String customerName, String customerCpf, String whatsapp, String plate, String model, Integer manufactureYear, boolean zeroKm, String observation) {
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalArgumentException("Informe o nome do associado.");
        }
        String cleanCustomerName = cleanName(customerName);
        if (cleanCustomerName.length() > 120) {
            throw new IllegalArgumentException("O nome do associado deve possuir no máximo 120 caracteres.");
        }

        String cpfDigits = customerCpf == null ? "" : customerCpf.replaceAll("\\D", "");
        if (!cpfDigits.isBlank() && cpfDigits.length() != 11) {
            throw new IllegalArgumentException("Informe um CPF válido.");
        }

        String phoneDigits = whatsapp == null ? "" : whatsapp.replaceAll("\\D", "");
        if (!phoneDigits.isBlank() && (phoneDigits.length() < 10 || phoneDigits.length() > 13)) {
            throw new IllegalArgumentException("Informe um WhatsApp válido com DDD.");
        }

        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Informe o modelo do veículo.");
        }
        String cleanModel = model.trim().replaceAll("\\s+", " ");
        if (cleanModel.length() > 120) {
            throw new IllegalArgumentException("O modelo deve possuir no máximo 120 caracteres.");
        }
        if (manufactureYear == null || manufactureYear < 1950 || manufactureYear > 2100) {
            throw new IllegalArgumentException("Informe um ano de fabricação válido.");
        }

        this.customerName = cleanCustomerName;
        this.customerCpf = cpfDigits.isBlank() ? null : cpfDigits;
        this.whatsapp = phoneDigits.isBlank() ? null : phoneDigits;
        if (!java.util.Objects.equals(this.model, cleanModel)
                || !java.util.Objects.equals(this.manufactureYear, manufactureYear)
                || this.zeroKm != zeroKm) {
            updateFipeMetadata(null, null, null);
        }
        this.model = cleanModel;
        this.manufactureYear = manufactureYear;
        this.zeroKm = zeroKm;
        this.plate = normalizePlate(plate, zeroKm);
        this.observation = cleanObservation(observation);
    }

    public BigDecimal selectedOptionalMonthlyValue() {
        return selectedOptionals.stream()
                .map(QuotationOptionalCoverage::getMonthlyPrice)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sincroniza os serviços adicionais escolhidos com o catálogo atual do plano.
     * O código da cotação continua sendo o mesmo, porém nome/detalhe/preço do
     * adicional passam a refletir o que está cadastrado no momento da correção.
     */
    public void synchronizeSelectedOptionalsFromCatalog(
            java.util.List<PlanCoverage> catalogCoverages, Set<String> selectedBenefitCodes
    ) {
        Set<String> selected = selectedBenefitCodes == null ? Set.of() : selectedBenefitCodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        java.util.Map<String, PlanCoverage> currentOptionals = (catalogCoverages == null
                ? java.util.List.<PlanCoverage>of() : catalogCoverages).stream()
                .filter(item -> item != null && item.getCoverage() != null)
                .filter(item -> item.getStatus() == CoverageStatus.OPTIONAL)
                .filter(item -> item.getCoverage().getCode() != null)
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.getCoverage().getCode().trim().toUpperCase(Locale.ROOT),
                        java.util.function.Function.identity(),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));

        selectedOptionals.removeIf(optional -> {
            String code = optional.getCoverageCode() == null ? ""
                    : optional.getCoverageCode().trim().toUpperCase(Locale.ROOT);
            return !selected.contains(code) || !currentOptionals.containsKey(code);
        });

        for (String code : selected) {
            PlanCoverage catalog = currentOptionals.get(code);
            if (catalog == null) continue;
            BigDecimal price = catalog.getMonthlyPrice();
            if (price == null || price.signum() < 0) {
                throw new IllegalArgumentException(
                        "O serviço adicional " + catalog.getCoverage().getName() + " não possui valor mensal válido no catálogo."
                );
            }
            QuotationOptionalCoverage existing = selectedOptionals.stream()
                    .filter(optional -> optional.getCoverageCode() != null
                            && optional.getCoverageCode().equalsIgnoreCase(code))
                    .findFirst().orElse(null);
            if (existing == null) {
                selectedOptionals.add(QuotationOptionalCoverage.create(
                        this, catalog.getCoverage().getCode(), catalog.getCoverage().getName(),
                        catalog.getDetail(), price.setScale(2, RoundingMode.HALF_UP)
                ));
            } else {
                existing.updateFromCatalog(
                        catalog.getCoverage().getName(), catalog.getDetail(),
                        price.setScale(2, RoundingMode.HALF_UP)
                );
            }
        }
    }

    /**
     * Participação efetiva dos adicionais na mensalidade já contratada.
     * Adicionais nunca recebem desconto: o valor contratado é sempre o preço
     * integral cadastrado para cada serviço adicional.
     */
    public BigDecimal selectedOptionalContractMonthlyValue() {
        return selectedOptionalMonthlyValue();
    }

    public void updateFipeValue(BigDecimal requestedFipeValue) {
        if (requestedFipeValue == null || requestedFipeValue.signum() <= 0) {
            throw new IllegalArgumentException("Informe um valor FIPE válido.");
        }
        this.fipeValue = requestedFipeValue.setScale(2, RoundingMode.HALF_UP);
        enforceAutomaticBenefitRules();
    }

    /**
     * Correção manual dos valores que serão efetivamente usados no contrato.
     * A mensalidade informada aqui é o total final contratado. Para manter o
     * detalhamento do PDF consistente, preservamos o desconto já aceito e
     * recalculamos o equivalente antes do desconto, a mensalidade-base e extras.
     */
    public void updateContractValues(BigDecimal requestedFipeValue, BigDecimal requestedMonthlyValue) {
        if (requestedFipeValue == null || requestedFipeValue.signum() <= 0) {
            throw new IllegalArgumentException("Informe um valor FIPE válido.");
        }
        if (requestedMonthlyValue == null || requestedMonthlyValue.signum() <= 0) {
            throw new IllegalArgumentException("Informe uma mensalidade válida.");
        }

        BigDecimal fipe = requestedFipeValue.setScale(2, RoundingMode.HALF_UP);
        BigDecimal monthly = requestedMonthlyValue.setScale(2, RoundingMode.HALF_UP);

        BigDecimal fixedExtras = mandatoryMonthlyFee == null ? BigDecimal.ZERO : mandatoryMonthlyFee;
        for (QuotationOptionalCoverage optional : selectedOptionals) {
            if (optional.getMonthlyPrice() != null) {
                fixedExtras = fixedExtras.add(optional.getMonthlyPrice());
            }
        }
        fixedExtras = fixedExtras.setScale(2, RoundingMode.HALF_UP);

        // O desconto originalmente aceito incide somente sobre a mensalidade-base.
        // A correção manual informa o total FINAL; retiramos taxas/adicionais integrais
        // e reconstruímos apenas a base anterior ao desconto.
        int percent = discountPercent == null ? 0 : discountPercent;
        BigDecimal factor = BigDecimal.valueOf(100 - percent)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
        if (factor.signum() <= 0) {
            throw new IllegalArgumentException("O desconto atual da cotação é inválido para corrigir a mensalidade.");
        }
        if (monthly.compareTo(fixedExtras) < 0) {
            throw new IllegalArgumentException(
                    "A mensalidade final não pode ser menor que as taxas e adicionais contratados (R$ "
                            + fixedExtras.toPlainString().replace('.', ',') + ")."
            );
        }
        BigDecimal discountedBase = monthly.subtract(fixedExtras).setScale(2, RoundingMode.HALF_UP);
        BigDecimal baseBeforeDiscount = discountedBase.divide(factor, 2, RoundingMode.HALF_UP);
        BigDecimal preDiscountTarget = baseBeforeDiscount.add(fixedExtras).setScale(2, RoundingMode.HALF_UP);

        this.fipeValue = fipe;
        this.preDiscountMonthlyValue = preDiscountTarget;
        this.baseMonthlyValue = baseBeforeDiscount;
        // O valor informado pela equipe é a fonte de verdade do novo contrato.
        // Mantemos o desconto/branding originais e o total final exatamente como confirmado.
        this.monthlyValue = monthly;
    }

    /**
     * Aplica a alteração já confirmada pelo associado. O valor proposto sempre
     * representa o total com os adicionais originalmente contratados. Caso o
     * associado opte por não mantê-los, eles são removidos e o respectivo valor
     * mensal é descontado antes de consolidar o novo contrato.
     */
    public void applyConfirmedContractChange(
            BigDecimal requestedFipeValue,
            BigDecimal requestedMonthlyWithOptionals,
            boolean keepOptionals
    ) {
        BigDecimal optionals = selectedOptionalContractMonthlyValue();
        BigDecimal targetMonthly = requestedMonthlyWithOptionals;
        if (!keepOptionals && optionals.signum() > 0) {
            targetMonthly = requestedMonthlyWithOptionals.subtract(optionals);
            selectedOptionals.clear();
        }
        updateContractValues(requestedFipeValue, targetMonthly);
    }

    public boolean usesFipeThirdPartyRule() {
        return "CAR_NATIONAL".equalsIgnoreCase(categoryCode)
                || "CAR_IMPORTED".equalsIgnoreCase(categoryCode)
                || "UTILITY".equalsIgnoreCase(categoryCode);
    }

    /**
     * Indica os planos que usam a cobertura-base de terceiros obrigatória.
     * Os valores e a faixa FIPE não ficam nesta entidade: são administrados
     * pelo painel e resolvidos por ThirdPartyRuleSettingsService.
     */
    public boolean usesProtectedThirdPartyPlanRule() {
        return usesProtectedThirdPartyPlanRule(selectedPlanCode);
    }

    public static boolean usesProtectedThirdPartyPlanRule(String planCode) {
        if (planCode == null) return false;
        String code = planCode.trim().toUpperCase(Locale.ROOT);
        return "CAR_ECONOMICO".equals(code)
                || "UTILITY_ECONOMICO".equals(code)
                || "CAR_COMPLETO".equals(code)
                || "UTILITY_COMPLETO".equals(code);
    }

    public static boolean isDiscountExcludedBenefit(String code) {
        if (code == null) return false;
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return "NATURAL_PHENOMENA".equals(normalized) || "SMALL_REPAIRS".equals(normalized);
    }

    public Set<String> finalSelectedCoverageCodes() {
        Set<String> codes = new LinkedHashSet<>();
        coverageSnapshots.stream()
                .filter(QuotationCoverageSnapshot::isFinalSelected)
                .map(QuotationCoverageSnapshot::getCoverageCode)
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .forEach(codes::add);
        selectedOptionals.stream()
                .map(QuotationOptionalCoverage::getCoverageCode)
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .forEach(codes::add);
        return codes;
    }

    /**
     * Reaplica as regras comerciais obrigatórias sem alterar o preço. Deve ser
     * executado depois que os snapshots do catálogo existirem ou quando a FIPE/
     * desconto forem alterados.
     */
    public void enforceAutomaticBenefitRules() {
        Set<String> optionalCodes = selectedOptionals.stream()
                .map(QuotationOptionalCoverage::getCoverageCode)
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        for (QuotationCoverageSnapshot snapshot : coverageSnapshots) {
            String code = snapshot.getCoverageCode() == null
                    ? "" : snapshot.getCoverageCode().trim().toUpperCase(Locale.ROOT);
            if ((discountPercent == null ? 0 : discountPercent) > 0 && isDiscountExcludedBenefit(code)) {
                snapshot.setFinalSelected(false);
                continue;
            }
            if (snapshot.getCoverageStatus() == CoverageStatus.OPTIONAL) {
                snapshot.setFinalSelected(optionalCodes.contains(code));
            }
        }
    }

    /** Aplica a seleção final produzida pelo motor de regras do catálogo. */
    public void applyBenefitSelection(Set<String> requestedBenefitCodes) {
        Set<String> selected = requestedBenefitCodes == null ? Set.of() : requestedBenefitCodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (QuotationCoverageSnapshot snapshot : coverageSnapshots) {
            String code = snapshot.getCoverageCode() == null ? ""
                    : snapshot.getCoverageCode().trim().toUpperCase(Locale.ROOT);
            snapshot.setFinalSelected(selected.contains(code));
        }
        selectedOptionals.removeIf(optional -> optional.getCoverageCode() == null
                || !selected.contains(optional.getCoverageCode().trim().toUpperCase(Locale.ROOT)));
    }

    /**
     * Consolida a revisão comercial final do dossiê. A equipe autorizada pode
     * corrigir os benefícios, mas regras automáticas não podem ser burladas:
     * terceiros segue FIPE + desconto para carros/utilitários; desconto também
     * remove fenômenos da natureza e pequenos reparos.
     */
    public void applyCommercialRevision(
            BigDecimal requestedFipeValue,
            BigDecimal requestedMonthlyValue,
            Integer requestedDiscountPercent,
            RearWindowBranding requestedBranding,
            Set<String> requestedBenefitCodes
    ) {
        applyCommercialRevision(
                requestedFipeValue, requestedMonthlyValue, requestedDiscountPercent, requestedBranding, requestedBenefitCodes,
                baseMonthlyValue, mandatoryMonthlyFee, oneTimeFee, mandatoryFeeDescription, true
        );
    }

    /**
     * Consolida a revisão comercial usando a composição atual do catálogo.
     * Quando não há ajuste manual, o total é reconstruído a partir de:
     * plano-base com desconto + taxa mensal obrigatória vigente + adicionais selecionados pelo valor integral.
     * A mensalidade histórica não participa desse cálculo.
     */
    public void applyCommercialRevision(
            BigDecimal requestedFipeValue,
            BigDecimal requestedMonthlyValue,
            Integer requestedDiscountPercent,
            RearWindowBranding requestedBranding,
            Set<String> requestedBenefitCodes,
            BigDecimal catalogBaseMonthlyValue,
            BigDecimal catalogMandatoryMonthlyFee,
            BigDecimal catalogOneTimeFee,
            String catalogMandatoryFeeDescription,
            boolean manualMonthlyOverride
    ) {
        if (requestedFipeValue == null || requestedFipeValue.signum() <= 0) {
            throw new IllegalArgumentException("Informe um valor FIPE válido.");
        }
        this.fipeValue = requestedFipeValue.setScale(2, RoundingMode.HALF_UP);

        int percent = requestedDiscountPercent == null ? 0 : requestedDiscountPercent;
        Set<String> selected = requestedBenefitCodes == null ? Set.of() : requestedBenefitCodes.stream()
                .filter(java.util.Objects::nonNull)
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        applyDiscount(percent, requestedBranding);
        if (percent > 0) {
            selected.removeIf(Quotation::isDiscountExcludedBenefit);
        }

        for (QuotationCoverageSnapshot snapshot : coverageSnapshots) {
            String code = snapshot.getCoverageCode() == null ? "" : snapshot.getCoverageCode().trim().toUpperCase(Locale.ROOT);
            snapshot.setFinalSelected(selected.contains(code));
        }

        selectedOptionals.removeIf(optional -> {
            String code = optional.getCoverageCode() == null ? "" : optional.getCoverageCode().trim().toUpperCase(Locale.ROOT);
            return coverageSnapshots.stream()
                    .filter(snapshot -> snapshot.getCoverageCode().equalsIgnoreCase(code))
                    .findFirst()
                    .map(snapshot -> snapshot.getCoverageStatus() == CoverageStatus.OPTIONAL && !snapshot.isFinalSelected())
                    .orElse(false);
        });
        for (QuotationCoverageSnapshot snapshot : coverageSnapshots) {
            if (snapshot.getCoverageStatus() != CoverageStatus.OPTIONAL || !snapshot.isFinalSelected()) continue;
            boolean exists = selectedOptionals.stream().anyMatch(optional -> optional.getCoverageCode().equalsIgnoreCase(snapshot.getCoverageCode()));
            if (!exists) {
                BigDecimal price = snapshot.getMonthlyPrice() == null ? BigDecimal.ZERO : snapshot.getMonthlyPrice();
                selectedOptionals.add(QuotationOptionalCoverage.create(
                        this, snapshot.getCoverageCode(), snapshot.getCoverageName(), snapshot.getDetail(), price
                ));
            }
        }

        this.mandatoryMonthlyFee = catalogMandatoryMonthlyFee == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : catalogMandatoryMonthlyFee.setScale(2, RoundingMode.HALF_UP);
        this.oneTimeFee = catalogOneTimeFee == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : catalogOneTimeFee.setScale(2, RoundingMode.HALF_UP);
        this.mandatoryFeeDescription = catalogMandatoryFeeDescription;

        if (!manualMonthlyOverride && catalogBaseMonthlyValue != null && catalogBaseMonthlyValue.signum() >= 0) {
            this.baseMonthlyValue = catalogBaseMonthlyValue.setScale(2, RoundingMode.HALF_UP);
            this.preDiscountMonthlyValue = this.baseMonthlyValue
                    .add(this.mandatoryMonthlyFee)
                    .add(selectedOptionalMonthlyValue())
                    .setScale(2, RoundingMode.HALF_UP);
            applyDiscountPricing(percent, requestedBranding, requestedMonthlyValue);
        } else {
            updateContractValues(requestedFipeValue, requestedMonthlyValue);
        }
    }

    public void updateObservation(String observation) {
        this.observation = cleanObservation(observation);
    }

    public void registerDriveFolder(String folderId, String folderUrl) {
        this.driveFolderId = folderId;
        this.driveFolderUrl = folderUrl;
    }

    public void replaceInspectionPhotos() {
        inspectionPhotos.clear();
        inspectionCompletedAt = null;
    }

    public void addInspectionPhoto(
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder,
            String driveFileId,
            String driveFileUrl
    ) {
        inspectionPhotos.add(InspectionPhoto.create(
                this,
                label,
                fileName,
                contentType,
                fileSize,
                sortOrder,
                driveFileId,
                driveFileUrl
        ));
    }

    public void completeInspection(String pdfFileId, String pdfUrl) {
        this.drivePdfFileId = pdfFileId;
        this.drivePdfUrl = pdfUrl;
        this.inspectionCompletedAt = OffsetDateTime.now();
    }

    public void decide(QuoteStatus newStatus) {
        if (newStatus != QuoteStatus.ACCEPTED && newStatus != QuoteStatus.DECLINED) {
            throw new IllegalArgumentException("A decisão deve ser ACCEPTED ou DECLINED.");
        }
        this.status = newStatus;
        this.decidedAt = OffsetDateTime.now();
    }

    public void adminReview(QuoteStatus newStatus, String note) {
        if (newStatus == null) {
            throw new IllegalArgumentException("Informe o novo status da cotação.");
        }
        this.status = newStatus;
        this.adminNote = cleanNote(note);
        this.reviewedAt = OffsetDateTime.now();
        if (newStatus == QuoteStatus.ACCEPTED || newStatus == QuoteStatus.DECLINED || newStatus == QuoteStatus.CANCELLED) {
            this.decidedAt = this.reviewedAt;
        } else if (newStatus == QuoteStatus.CREATED || newStatus == QuoteStatus.UNDER_REVIEW) {
            this.decidedAt = null;
        }
    }

    private String cleanNote(String note) {
        if (note == null || note.isBlank()) return null;
        String clean = note.trim();
        if (clean.length() > 1200) throw new IllegalArgumentException("A observação deve possuir no máximo 1.200 caracteres.");
        return clean;
    }

    public UUID getId() { return id; }
    public String getQuoteNumber() { return quoteNumber; }
    public QuoteOrigin getOrigin() { return origin; }
    public Consultant getConsultant() { return consultant; }
    public String getConsultantName() { return consultantName; }
    public String getCustomerName() { return customerName; }
    public String getCustomerCpf() { return customerCpf; }
    public String getWhatsapp() { return whatsapp; }
    public String getPlate() { return plate; }
    public String getModel() { return model; }
    public Integer getManufactureYear() { return manufactureYear; }
    public boolean isZeroKm() { return zeroKm; }
    public String getVehicleFipeCode() { return vehicleFipeCode; }
    public String getVehicleFipeReference() { return vehicleFipeReference; }
    public String getVehicleFipeSource() { return vehicleFipeSource; }
    public void updateFipeMetadata(String code, String reference, String source) {
        vehicleFipeCode = code;
        vehicleFipeReference = reference;
        vehicleFipeSource = source;
    }
    public BigDecimal getFipeValue() { return fipeValue; }
    public Boolean getAuctionOrChassisRemarked() { return auctionOrChassisRemarked; }
    public Integer getIndemnityFipePercent() { return indemnityFipePercent == null ? 100 : indemnityFipePercent; }
    public String getCategoryCode() { return categoryCode; }
    public Region getRegion() { return region; }
    public PricingRegion getPricingRegion() { return pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion; }
    public MotorcycleOrigin getMotorcycleOrigin() { return motorcycleOrigin; }
    public Integer getMotorcycleCc() { return motorcycleCc; }
    public String getObservation() { return observation; }
    public String getResidenceCep() { return residenceCep; }
    public String getResidenceAddress() { return residenceAddress; }
    public Integer getBillingDueDay() { return billingDueDay; }
    public LocalDate getFirstBillingDueDate() { return firstBillingDueDate; }
    public String getSelectedPlanCode() { return selectedPlanCode; }
    public String getSelectedPlanName() { return selectedPlanName; }
    public BigDecimal getBaseMonthlyValue() { return baseMonthlyValue; }
    public BigDecimal getMonthlyValue() { return monthlyValue; }
    public BigDecimal getPreDiscountMonthlyValue() { return preDiscountMonthlyValue; }
    public Integer getDiscountPercent() { return discountPercent == null ? 0 : discountPercent; }
    public RearWindowBranding getRearWindowBranding() {
        return rearWindowBranding == null ? RearWindowBranding.NOT_APPLICABLE : rearWindowBranding;
    }
    public BigDecimal getMandatoryMonthlyFee() { return mandatoryMonthlyFee; }
    public BigDecimal getOneTimeFee() { return oneTimeFee; }
    public String getMandatoryFeeDescription() { return mandatoryFeeDescription; }
    public List<QuotationOptionalCoverage> getSelectedOptionals() { return selectedOptionals; }
    public Set<QuotationCoverageSnapshot> getCoverageSnapshots() { return coverageSnapshots; }
    public QuoteStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public String getAdminNote() { return adminNote; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public String getDriveFolderId() { return driveFolderId; }
    public String getDriveFolderUrl() { return driveFolderUrl; }
    public String getDrivePdfFileId() { return drivePdfFileId; }
    public String getDrivePdfUrl() { return drivePdfUrl; }
    public OffsetDateTime getInspectionCompletedAt() { return inspectionCompletedAt; }
    public Set<InspectionPhoto> getInspectionPhotos() { return inspectionPhotos; }
}
