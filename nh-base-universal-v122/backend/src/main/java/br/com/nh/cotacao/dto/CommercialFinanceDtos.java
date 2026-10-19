package br.com.nh.cotacao.dto;

import br.com.nh.cotacao.entity.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CommercialFinanceDtos {
    private CommercialFinanceDtos() {}

    public record CreateAssociateRequest(
            @NotBlank @Size(max=160) String name,
            @Size(max=20) String document,
            @Size(max=30) String whatsapp,
            @Email @Size(max=180) String email
    ) {}

    public record UpdateAssociateSelfRequest(
            @NotBlank @Size(max=160) String name,
            @Size(max=30) String whatsapp,
            @Email @Size(max=180) String email,
            LocalDate birthDate,
            @Size(max=30) String secondaryPhone,
            @Size(max=12) String postalCode,
            @Size(max=220) String addressLine,
            @Size(max=30) String addressNumber,
            @Size(max=120) String addressComplement,
            @Size(max=120) String neighborhood,
            @Size(max=120) String city,
            @Size(max=2) String state
    ) {}

    public record AssociateSummary(UUID id, String name, String document, String whatsapp, String email, boolean active, int vehicleCount) {}
    public record AssociateDetail(UUID id, String name, String document, String whatsapp, String email, boolean active, List<VehicleSummary> vehicles) {}
    public record AssociatePortalProfile(
            UUID id, String name, String document, String whatsapp, String email,
            LocalDate birthDate, String secondaryPhone, String postalCode, String addressLine,
            String addressNumber, String addressComplement, String neighborhood, String city, String state,
            boolean active, List<AssociateVehicleSummary> vehicles
    ) {}
    public record AssociateVehicleSummary(
            UUID id, UUID associateId, String associateName, String plate, String model, Integer manufactureYear, String planName,
            BigDecimal monthlyValue, VehicleBusinessStatus businessStatus, String businessStatusLabel,
            LocalDate nextDueDate, long paidCharges, long pendingCharges, long overdueCharges
    ) {}
    public record AssociateVehicleDetail(AssociateVehicleSummary vehicle, boolean asaasConnected, List<ChargeResponse> charges) {}
    public record SelfServiceChargeRequest(@NotNull FinancialBillingType billingType) {}

    public record VehicleSummary(
            UUID id, UUID associateId, String associateName, UUID consultantId, String consultantName,
            String plate, String model, Integer manufactureYear, String planCode, String planName,
            BigDecimal monthlyValue, BigDecimal adhesionValue, CommercialVehicleStatus status,
            AdhesionPaymentMethod adhesionPaymentMethod
    ) {}

    public record VehicleDetail(
            VehicleSummary vehicle, Integer billingDueDay, LocalDate firstBillingDueDate,
            FinancialBillingType recurringBillingType, boolean asaasConnected, String asaasSubscriptionId,
            List<ChargeResponse> charges
    ) {}

    public record ChargeResponse(
            UUID id, FinancialChargeType type, FinancialBillingType billingType, BigDecimal value,
            BigDecimal providerFee, BigDecimal totalCharged,
            LocalDate dueDate, FinancialChargeStatus status, OffsetDateTime paidAt,
            String invoiceUrl, String bankSlipUrl, String identificationField, String barCode,
            String pixPayload, String pixEncodedImage
    ) {}

    public record CreateChargeRequest(
            @NotNull FinancialChargeType type,
            @NotNull FinancialBillingType billingType,
            @NotNull @DecimalMin("0.01") BigDecimal value,
            @NotNull LocalDate dueDate
    ) {}

    public record UpdatePixRequest(@NotNull PixKeyType type, @NotBlank @Size(max=255) String key) {}
    public record UpdateCommissionRequest(@NotNull ConsultantCommissionMode mode, @NotNull @DecimalMin("0.00") BigDecimal value) {}
    public record WithdrawalRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            ConsultantPayoutMethod payoutMethod,
            @Size(max=1200) String bankDetails
    ) {}

    public record WithdrawalResponse(
            UUID id, BigDecimal amount, BigDecimal transactionFee, BigDecimal netAmount,
            ConsultantPayoutMethod payoutMethod, PixKeyType pixKeyType, String maskedPixKey, String bankDetails,
            WithdrawalStatus status, String failReason, OffsetDateTime createdAt, OffsetDateTime completedAt
    ) {}

    public record ConsultantFinanceSummary(
            UUID consultantId, String consultantName,
            ConsultantCommissionMode commissionMode, BigDecimal commissionValue,
            PixKeyType payoutPixKeyType, String maskedPayoutPixKey,
            long paidClosings, int requiredClosings, boolean withdrawalUnlocked,
            BigDecimal lockedBalance, BigDecimal availableBalance, BigDecimal totalReceivable,
            BigDecimal paidWithdrawals, boolean asaasEnabled, BigDecimal withdrawalFee,
            long salesThisMonth, String careerLevel, long activePayingBase,
            BigDecimal activeBaseRevenue, BigDecimal commissionThisMonth,
            CommissionBreakdown commissionBreakdown,
            List<WithdrawalResponse> withdrawals
    ) {}

    public record CommissionBreakdown(
            String referenceMonth, long paidNewSales, BigDecimal ladderOrBaseCommission,
            BigDecimal motorcycleSalesBonus, BigDecimal lightVehicleSalesBonus, BigDecimal utilityTruckSalesBonus,
            BigDecimal referralBonus, BigDecimal rankingBonus, BigDecimal fuelAllowance,
            BigDecimal adhesionCommission, BigDecimal total, BigDecimal percentageApplied,
            String calculationMode, String nextCareerMilestone
    ) {}

    public record AdminConsultantFinanceRow(
            UUID consultantId, String consultantName, long paidClosings,
            BigDecimal lockedBalance, BigDecimal availableBalance, BigDecimal paidWithdrawals,
            ConsultantCommissionMode commissionMode, BigDecimal commissionValue
    ) {}
}
