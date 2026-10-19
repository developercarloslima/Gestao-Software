package br.com.nh.cotacao.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="consultant_withdrawals")
public class ConsultantWithdrawal {
 @Id private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="consultant_id",nullable=false) private Consultant consultant;
 @Column(nullable=false,precision=14,scale=2) private BigDecimal amount;
 @Column(name="transaction_fee",nullable=false,precision=14,scale=2) private BigDecimal transactionFee;
 @Column(name="net_amount",nullable=false,precision=14,scale=2) private BigDecimal netAmount;
 @Enumerated(EnumType.STRING) @Column(name="payout_method",nullable=false,length=20) private ConsultantPayoutMethod payoutMethod;
 @Enumerated(EnumType.STRING) @Column(name="pix_key_type",length=20) private PixKeyType pixKeyType;
 @Column(name="pix_key",length=255) private String pixKey;
 @Column(name="bank_details",columnDefinition="text") private String bankDetails;
 @Column(name="finance_note",length=700) private String financeNote;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private WithdrawalStatus status;
 @Column(name="provider_transfer_id",length=100) private String providerTransferId;
 @Column(name="fail_reason",length=700) private String failReason;
 @Column(name="created_at",nullable=false) private OffsetDateTime createdAt;
 @Column(name="updated_at",nullable=false) private OffsetDateTime updatedAt;
 @Column(name="completed_at") private OffsetDateTime completedAt;
 protected ConsultantWithdrawal(){}
 public static ConsultantWithdrawal createPix(Consultant c,BigDecimal amount,BigDecimal fee,PixKeyType type,String key){
  ConsultantWithdrawal w=base(c,amount,fee,ConsultantPayoutMethod.PIX);w.pixKeyType=type;w.pixKey=key;return w;
 }
 public static ConsultantWithdrawal createTed(Consultant c,BigDecimal amount,String bankDetails){
  ConsultantWithdrawal w=base(c,amount,BigDecimal.ZERO,ConsultantPayoutMethod.TED);w.bankDetails=clean(bankDetails,4000);return w;
 }
 private static ConsultantWithdrawal base(Consultant c,BigDecimal amount,BigDecimal fee,ConsultantPayoutMethod method){ConsultantWithdrawal w=new ConsultantWithdrawal();w.id=UUID.randomUUID();w.consultant=c;w.amount=amount;w.transactionFee=fee==null?BigDecimal.ZERO:fee.max(BigDecimal.ZERO);w.netAmount=w.amount.subtract(w.transactionFee).max(BigDecimal.ZERO);w.payoutMethod=method;w.status=WithdrawalStatus.REQUESTED;w.createdAt=OffsetDateTime.now();w.updatedAt=w.createdAt;return w;}
 public void processing(String providerId){this.status=WithdrawalStatus.PROCESSING;this.providerTransferId=providerId;this.updatedAt=OffsetDateTime.now();}
 public void paid(){this.status=WithdrawalStatus.PAID;this.completedAt=OffsetDateTime.now();this.updatedAt=this.completedAt;}
 public void failed(String reason){this.status=WithdrawalStatus.FAILED;this.failReason=clean(reason,700);this.updatedAt=OffsetDateTime.now();}
 public void cancelled(String reason){this.status=WithdrawalStatus.CANCELLED;this.failReason=clean(reason,700);this.updatedAt=OffsetDateTime.now();}
 public void financeNote(String note){this.financeNote=clean(note,700);this.updatedAt=OffsetDateTime.now();}
 private static String clean(String v,int max){if(v==null||v.isBlank())return null;String x=v.trim();return x.length()>max?x.substring(0,max):x;}
 public UUID getId(){return id;}public Consultant getConsultant(){return consultant;}public BigDecimal getAmount(){return amount;}public BigDecimal getTransactionFee(){return transactionFee;}public BigDecimal getNetAmount(){return netAmount;}public ConsultantPayoutMethod getPayoutMethod(){return payoutMethod;}public PixKeyType getPixKeyType(){return pixKeyType;}public String getPixKey(){return pixKey;}public String getBankDetails(){return bankDetails;}public String getFinanceNote(){return financeNote;}public WithdrawalStatus getStatus(){return status;}public String getProviderTransferId(){return providerTransferId;}public String getFailReason(){return failReason;}public OffsetDateTime getCreatedAt(){return createdAt;}public OffsetDateTime getUpdatedAt(){return updatedAt;}public OffsetDateTime getCompletedAt(){return completedAt;}
}
