package br.com.nh.cotacao.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name="financial_charges")
public class FinancialCharge {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="vehicle_id", nullable=false) private CommercialVehicle vehicle;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="associate_id", nullable=false) private Associate associate;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="consultant_id") private Consultant consultant;
    @Column(nullable=false, length=20) private String provider;
    @Column(name="provider_payment_id", length=100) private String providerPaymentId;
    @Column(name="provider_subscription_id", length=100) private String providerSubscriptionId;
    @Enumerated(EnumType.STRING) @Column(name="charge_type", nullable=false, length=20) private FinancialChargeType chargeType;
    @Enumerated(EnumType.STRING) @Column(name="billing_type", nullable=false, length=20) private FinancialBillingType billingType;
    @Column(nullable=false, precision=14, scale=2) private BigDecimal value;
    @Column(name="provider_fee",nullable=false,precision=14,scale=2) private BigDecimal providerFee;
    @Column(name="gross_value",nullable=false,precision=14,scale=2) private BigDecimal grossValue;
    @Column(name="due_date") private LocalDate dueDate;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=30) private FinancialChargeStatus status;
    @Column(name="paid_at") private OffsetDateTime paidAt;
    @Column(name="invoice_url", length=700) private String invoiceUrl;
    @Column(name="bank_slip_url", length=700) private String bankSlipUrl;
    @Column(name="identification_field", length=120) private String identificationField;
    @Column(name="bar_code", length=120) private String barCode;
    @Column(name="pix_payload", columnDefinition="text") private String pixPayload;
    @Column(name="pix_encoded_image", columnDefinition="text") private String pixEncodedImage;
    @Column(name="external_reference", length=180) private String externalReference;
    @Column(name="created_at", nullable=false) private OffsetDateTime createdAt;
    @Column(name="updated_at", nullable=false) private OffsetDateTime updatedAt;

    protected FinancialCharge() {}
    public static FinancialCharge create(CommercialVehicle v, FinancialChargeType type, FinancialBillingType billing, BigDecimal value, LocalDate dueDate) {
        FinancialCharge c = new FinancialCharge(); c.id=UUID.randomUUID(); c.vehicle=v; c.associate=v.getAssociate(); c.consultant=v.getConsultant(); c.provider="ASAAS";
        c.chargeType=type; c.billingType=billing; c.value=value == null ? BigDecimal.ZERO : value; c.providerFee=BigDecimal.ZERO; c.grossValue=c.value; c.dueDate=dueDate; c.status=FinancialChargeStatus.PENDING;
        c.createdAt=OffsetDateTime.now(); c.updatedAt=c.createdAt; c.externalReference=type.name().toLowerCase(java.util.Locale.ROOT)+":"+v.getId()+":"+c.id; return c;
    }
    public void applyProvider(String paymentId,String subscriptionId,String invoiceUrl,String bankSlipUrl,String identificationField,String barCode,String pixPayload,String pixImage) {
        if(paymentId!=null&&!paymentId.isBlank())this.providerPaymentId=paymentId; if(subscriptionId!=null&&!subscriptionId.isBlank())this.providerSubscriptionId=subscriptionId;
        if(invoiceUrl!=null)this.invoiceUrl=invoiceUrl; if(bankSlipUrl!=null)this.bankSlipUrl=bankSlipUrl; if(identificationField!=null)this.identificationField=identificationField;
        if(barCode!=null)this.barCode=barCode; if(pixPayload!=null)this.pixPayload=pixPayload; if(pixImage!=null)this.pixEncodedImage=pixImage; this.updatedAt=OffsetDateTime.now();
    }
    public void updateFromWebhook(FinancialChargeStatus status, BigDecimal providerGrossValue, LocalDate dueDate, OffsetDateTime paidAt, String invoiceUrl, String bankSlipUrl) {
        if(status!=null)this.status=status; if(providerGrossValue!=null){this.grossValue=providerGrossValue;this.providerFee=providerGrossValue.subtract(this.value).max(BigDecimal.ZERO);} if(dueDate!=null)this.dueDate=dueDate; if(paidAt!=null)this.paidAt=paidAt;
        if(invoiceUrl!=null)this.invoiceUrl=invoiceUrl; if(bankSlipUrl!=null)this.bankSlipUrl=bankSlipUrl; this.updatedAt=OffsetDateTime.now();
    }
    public void applyProviderFee(BigDecimal fee){
        BigDecimal normalized=fee==null?BigDecimal.ZERO:fee.max(BigDecimal.ZERO).setScale(2,java.math.RoundingMode.HALF_UP);
        this.providerFee=normalized;this.grossValue=this.value.add(normalized).setScale(2,java.math.RoundingMode.HALF_UP);this.updatedAt=OffsetDateTime.now();
    }
    public void markReceived(OffsetDateTime paidAt){this.status=FinancialChargeStatus.RECEIVED;this.paidAt=paidAt==null?OffsetDateTime.now():paidAt;this.updatedAt=OffsetDateTime.now();}
    public void setChargeType(FinancialChargeType type){if(type!=null){this.chargeType=type;this.updatedAt=OffsetDateTime.now();}}
    public void markError(){this.status=FinancialChargeStatus.ERROR;this.updatedAt=OffsetDateTime.now();}
    public void markPending(){this.status=FinancialChargeStatus.PENDING;this.updatedAt=OffsetDateTime.now();}
    public UUID getId(){return id;} public CommercialVehicle getVehicle(){return vehicle;} public Associate getAssociate(){return associate;} public Consultant getConsultant(){return consultant;}
    public String getProvider(){return provider;} public String getProviderPaymentId(){return providerPaymentId;} public String getProviderSubscriptionId(){return providerSubscriptionId;}
    public FinancialChargeType getChargeType(){return chargeType;} public FinancialBillingType getBillingType(){return billingType;} public BigDecimal getValue(){return value;} public BigDecimal getProviderFee(){return providerFee;} public BigDecimal getGrossValue(){return grossValue;} public LocalDate getDueDate(){return dueDate;}
    public FinancialChargeStatus getStatus(){return status;} public OffsetDateTime getPaidAt(){return paidAt;} public String getInvoiceUrl(){return invoiceUrl;} public String getBankSlipUrl(){return bankSlipUrl;}
    public String getIdentificationField(){return identificationField;} public String getBarCode(){return barCode;} public String getPixPayload(){return pixPayload;} public String getPixEncodedImage(){return pixEncodedImage;}
    public String getExternalReference(){return externalReference;} public OffsetDateTime getCreatedAt(){return createdAt;} public OffsetDateTime getUpdatedAt(){return updatedAt;}
}
