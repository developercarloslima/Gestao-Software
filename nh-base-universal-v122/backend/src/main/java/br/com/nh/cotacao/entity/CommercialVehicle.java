package br.com.nh.cotacao.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "commercial_vehicles")
public class CommercialVehicle {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name="associate_id", nullable=false) private Associate associate;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name="consultant_id") private Consultant consultant;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name="quotation_id") private Quotation quotation;
    @Column(length=10) private String plate;
    @Column(nullable=false, length=140) private String model;
    @Column(name="manufacture_year") private Integer manufactureYear;
    @Column(name="selected_plan_code", length=80) private String selectedPlanCode;
    @Column(name="selected_plan_name", length=140) private String selectedPlanName;
    @Column(name="monthly_value", nullable=false, precision=14, scale=2) private BigDecimal monthlyValue;
    @Column(name="adhesion_value", nullable=false, precision=14, scale=2) private BigDecimal adhesionValue;
    @Column(name="billing_due_day") private Integer billingDueDay;
    @Column(name="first_billing_due_date") private LocalDate firstBillingDueDate;
    @Enumerated(EnumType.STRING) @Column(name="adhesion_payment_method", nullable=false, length=20) private AdhesionPaymentMethod adhesionPaymentMethod;
    @Enumerated(EnumType.STRING) @Column(name="recurring_billing_type", nullable=false, length=20) private FinancialBillingType recurringBillingType;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private CommercialVehicleStatus status;
    @Column(name="asaas_subscription_id", length=80) private String asaasSubscriptionId;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;

    protected CommercialVehicle() {}

    public static CommercialVehicle fromQuotation(Quotation q, Associate associate, AdhesionPaymentMethod adhesionMethod) {
        CommercialVehicle v = new CommercialVehicle();
        v.id = UUID.randomUUID();
        v.associate = associate;
        v.quotation = q;
        v.adhesionPaymentMethod = adhesionMethod == null ? AdhesionPaymentMethod.BOLETO : adhesionMethod;
        v.recurringBillingType = FinancialBillingType.BOLETO;
        v.status = q.getStatus() == QuoteStatus.ACCEPTED ? CommercialVehicleStatus.ACTIVE : CommercialVehicleStatus.DRAFT;
        v.createdAt = OffsetDateTime.now();
        v.syncFromQuotation(q);
        return v;
    }

    public void syncFromQuotation(Quotation q) {
        this.quotation = q;
        this.consultant = q.getConsultant();
        this.plate = blankToNull(q.getPlate());
        this.model = q.getModel();
        this.manufactureYear = q.getManufactureYear();
        this.selectedPlanCode = q.getSelectedPlanCode();
        this.selectedPlanName = q.getSelectedPlanName();
        this.monthlyValue = zero(q.getMonthlyValue());
        this.adhesionValue = zero(q.getOneTimeFee());
        this.billingDueDay = q.getBillingDueDay();
        this.firstBillingDueDate = q.getFirstBillingDueDate();
        this.updatedAt = OffsetDateTime.now();
    }

    public void activate() { this.status = CommercialVehicleStatus.ACTIVE; this.updatedAt = OffsetDateTime.now(); }
    public void setAssociate(Associate associate) { this.associate = associate; this.updatedAt = OffsetDateTime.now(); }
    public void setAdhesionPaymentMethod(AdhesionPaymentMethod method) { if (method != null) this.adhesionPaymentMethod = method; this.updatedAt = OffsetDateTime.now(); }
    public void setRecurringBillingType(FinancialBillingType type) { if (type == FinancialBillingType.BOLETO || type == FinancialBillingType.PIX || type == FinancialBillingType.CREDIT_CARD) this.recurringBillingType = type; this.updatedAt = OffsetDateTime.now(); }
    public void setAsaasSubscriptionId(String id) { this.asaasSubscriptionId = blankToNull(id); this.updatedAt = OffsetDateTime.now(); }
    private static BigDecimal zero(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public UUID getId(){return id;} public Associate getAssociate(){return associate;} public Consultant getConsultant(){return consultant;} public Quotation getQuotation(){return quotation;}
    public String getPlate(){return plate;} public String getModel(){return model;} public Integer getManufactureYear(){return manufactureYear;}
    public String getSelectedPlanCode(){return selectedPlanCode;} public String getSelectedPlanName(){return selectedPlanName;} public BigDecimal getMonthlyValue(){return monthlyValue;}
    public BigDecimal getAdhesionValue(){return adhesionValue;} public Integer getBillingDueDay(){return billingDueDay;} public LocalDate getFirstBillingDueDate(){return firstBillingDueDate;}
    public AdhesionPaymentMethod getAdhesionPaymentMethod(){return adhesionPaymentMethod;} public FinancialBillingType getRecurringBillingType(){return recurringBillingType;}
    public CommercialVehicleStatus getStatus(){return status;} public String getAsaasSubscriptionId(){return asaasSubscriptionId;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
