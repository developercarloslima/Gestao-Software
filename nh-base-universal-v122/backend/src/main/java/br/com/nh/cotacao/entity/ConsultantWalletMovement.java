package br.com.nh.cotacao.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity @Table(name="consultant_wallet_movements")
public class ConsultantWalletMovement {
 @Id private UUID id;
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="consultant_id",nullable=false) private Consultant consultant;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="vehicle_id") private CommercialVehicle vehicle;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="charge_id") private FinancialCharge charge;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="withdrawal_id") private ConsultantWithdrawal withdrawal;
 @Enumerated(EnumType.STRING) @Column(name="movement_type",nullable=false,length=20) private WalletMovementType movementType;
 @Column(nullable=false,precision=14,scale=2) private BigDecimal amount;
 @Column(nullable=false) private boolean locked;
 @Column(length=500) private String note;
 @Column(name="created_at",nullable=false) private OffsetDateTime createdAt;
 @Column(name="unlocked_at") private OffsetDateTime unlockedAt;
 protected ConsultantWalletMovement(){}
 public static ConsultantWalletMovement commission(Consultant c,FinancialCharge charge,BigDecimal amount,boolean locked){ConsultantWalletMovement m=base(c,WalletMovementType.COMMISSION,amount,locked,"Comissão de cobrança paga");m.charge=charge;m.vehicle=charge.getVehicle();return m;}
 public static ConsultantWalletMovement withdrawal(Consultant c,ConsultantWithdrawal w){ConsultantWalletMovement m=base(c,WalletMovementType.WITHDRAWAL,w.getAmount().negate(),false,"Saque solicitado");m.withdrawal=w;return m;}
 public static ConsultantWalletMovement reversalForCharge(Consultant c,FinancialCharge charge,BigDecimal amount,boolean locked){ConsultantWalletMovement m=base(c,WalletMovementType.REVERSAL,amount.negate(),locked,"Estorno de comissão");m.charge=charge;m.vehicle=charge.getVehicle();return m;}
 public static ConsultantWalletMovement withdrawalReversal(Consultant c,ConsultantWithdrawal w){ConsultantWalletMovement m=base(c,WalletMovementType.REVERSAL,w.getAmount(),false,"Saque devolvido após falha/cancelamento");m.withdrawal=w;return m;}
 private static ConsultantWalletMovement base(Consultant c,WalletMovementType t,BigDecimal amount,boolean locked,String note){ConsultantWalletMovement m=new ConsultantWalletMovement();m.id=UUID.randomUUID();m.consultant=c;m.movementType=t;m.amount=amount;m.locked=locked;m.note=note;m.createdAt=OffsetDateTime.now();return m;}
 public void unlock(){if(locked){locked=false;unlockedAt=OffsetDateTime.now();}}
 public UUID getId(){return id;}public Consultant getConsultant(){return consultant;}public CommercialVehicle getVehicle(){return vehicle;}public FinancialCharge getCharge(){return charge;}public ConsultantWithdrawal getWithdrawal(){return withdrawal;}public WalletMovementType getMovementType(){return movementType;}public BigDecimal getAmount(){return amount;}public boolean isLocked(){return locked;}public String getNote(){return note;}public OffsetDateTime getCreatedAt(){return createdAt;}public OffsetDateTime getUnlockedAt(){return unlockedAt;}
}
