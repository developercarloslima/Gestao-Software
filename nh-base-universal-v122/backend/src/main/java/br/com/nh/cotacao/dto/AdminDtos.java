package br.com.nh.cotacao.dto;

import br.com.nh.cotacao.entity.CoverageStatus;
import br.com.nh.cotacao.entity.DamageCondition;
import br.com.nh.cotacao.entity.EventCondition;
import br.com.nh.cotacao.entity.DiscountBenefitAction;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.entity.InspectionAnalysisStage;
import br.com.nh.cotacao.entity.MotorcycleOrigin;
import br.com.nh.cotacao.entity.QuoteOrigin;
import br.com.nh.cotacao.entity.QuoteStatus;
import br.com.nh.cotacao.entity.Region;
import br.com.nh.cotacao.entity.PricingRegion;
import br.com.nh.cotacao.entity.RearWindowBranding;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() {}

    public record CategoryResponse(Long id, String code, String name, boolean active) {}

    public record UpdateCategoryStatusRequest(@NotNull Boolean active) {}

    public record PriceRangeResponse(
            Long id,
            Long planId,
            String planName,
            String category,
            String region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            BigDecimal minValue,
            BigDecimal maxValue,
            BigDecimal monthlyPrice
    ) {}

    public record OptionalPriceResponse(
            Long id,
            Long planId,
            String planName,
            String coverageName,
            String detail,
            BigDecimal monthlyPrice
    ) {}

    public record PromotionalMotorcyclePriceResponse(
            Long id,
            String tierCode,
            String label,
            Integer minCc,
            Integer maxCc,
            BigDecimal minFipe,
            BigDecimal maxFipe,
            BigDecimal monthlyPrice
    ) {}

    public record PlanAdminResponse(
            Long id,
            String name,
            String subtitle,
            Long categoryId,
            String category,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            Integer displayOrder,
            boolean active,
            BigDecimal extraAbove,
            BigDecimal extraStep,
            BigDecimal extraIncrement,
            BigDecimal extraBasePrice,
            BigDecimal trackerRequiredAbove,
            BigDecimal trackerInstallationFee,
            BigDecimal trackerMonthlyFee
    ) {}

    public record DiscountBenefitRuleResponse(
            Long id,
            Long coverageId,
            String coverageCode,
            String coverageName,
            DiscountBenefitAction action,
            String detailOverride,
            BigDecimal amountOverride
    ) {}

    public record DiscountRuleAdminResponse(
            Long id,
            String name,
            Integer percent,
            boolean active,
            Integer displayOrder,
            Integer minVehicleAgeYears,
            Integer maxVehicleAgeYears,
            BigDecimal minFipe,
            BigDecimal maxFipe,
            boolean requiresRearWindowPerforation,
            boolean applyToBase,
            boolean applyToMandatoryFee,
            boolean applyToOptionals,
            String notes,
            List<Long> planIds,
            List<String> planNames,
            List<DiscountBenefitRuleResponse> benefitRules
    ) {}

    public record DiscountBenefitRuleRequest(
            @NotNull Long coverageId,
            @NotNull DiscountBenefitAction action,
            @Size(max = 240) String detailOverride,
            @DecimalMin("0.00") BigDecimal amountOverride
    ) {}

    public record UpsertDiscountRuleRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Min(1) @Max(100) Integer percent,
            @NotNull Boolean active,
            @NotNull @Min(0) Integer displayOrder,
            @Min(0) Integer minVehicleAgeYears,
            @Min(0) Integer maxVehicleAgeYears,
            @DecimalMin("0.00") BigDecimal minFipe,
            @DecimalMin("0.00") BigDecimal maxFipe,
            @NotNull Boolean requiresRearWindowPerforation,
            @NotNull Boolean applyToBase,
            @NotNull Boolean applyToMandatoryFee,
            @NotNull Boolean applyToOptionals,
            @Size(max = 600) String notes,
            @NotEmpty List<@NotNull Long> planIds,
            @Size(max = 200) List<@Valid DiscountBenefitRuleRequest> benefitRules
    ) {
        public UpsertDiscountRuleRequest {
            planIds = planIds == null ? List.of() : List.copyOf(planIds);
            benefitRules = benefitRules == null ? List.of() : List.copyOf(benefitRules);
        }
    }

    public record PlanBenefitSelectionRequest(
            @NotNull Long coverageId,
            @NotNull CoverageStatus status,
            @Size(max = 240) String detail,
            @DecimalMin("0.00") BigDecimal monthlyPrice,
            @NotNull @Min(0) Integer sortOrder,
            DamageCondition damageCondition,
            EventCondition eventCondition,
            @Min(0) Integer towingRadiusKm
    ) {}

    public record CreatePlanRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 180) String subtitle,
            @NotNull Long categoryId,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            @NotNull @Min(0) Integer displayOrder,
            @NotNull Boolean active,
            @DecimalMin("0.00") BigDecimal extraAbove,
            @DecimalMin(value = "0.01") BigDecimal extraStep,
            @DecimalMin("0.00") BigDecimal extraIncrement,
            @DecimalMin("0.00") BigDecimal extraBasePrice,
            @DecimalMin("0.00") BigDecimal trackerRequiredAbove,
            @DecimalMin("0.00") BigDecimal trackerInstallationFee,
            @DecimalMin("0.00") BigDecimal trackerMonthlyFee,
            @Size(max = 500) List<@Valid PlanBenefitSelectionRequest> benefits
    ) {}

    public record UpdatePlanRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 180) String subtitle,
            @NotNull Long categoryId,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            @NotNull @Min(0) Integer displayOrder,
            @NotNull Boolean active,
            @DecimalMin("0.00") BigDecimal extraAbove,
            @DecimalMin(value = "0.01") BigDecimal extraStep,
            @DecimalMin("0.00") BigDecimal extraIncrement,
            @DecimalMin("0.00") BigDecimal extraBasePrice,
            @DecimalMin("0.00") BigDecimal trackerRequiredAbove,
            @DecimalMin("0.00") BigDecimal trackerInstallationFee,
            @DecimalMin("0.00") BigDecimal trackerMonthlyFee,
            @Size(max = 500) List<@Valid PlanBenefitSelectionRequest> benefits
    ) {}

    public record CoverageRuleAdminResponse(
            Long id,
            String categoryCode,
            BigDecimal minFipe,
            BigDecimal maxFipe,
            BigDecimal normalAmount,
            BigDecimal discountedAmount,
            Integer sortOrder
    ) {}

    public record CoverageRuleRequest(
            @Size(max = 50) String categoryCode,
            @NotNull @DecimalMin("0.00") BigDecimal minFipe,
            @DecimalMin("0.00") BigDecimal maxFipe,
            @NotNull @DecimalMin("0.00") BigDecimal normalAmount,
            @DecimalMin("0.00") BigDecimal discountedAmount,
            @NotNull @Min(0) Integer sortOrder
    ) {}

    public record ThirdPartyRuleSettingsResponse(
            String planCode,
            String planName,
            BigDecimal fipeThreshold,
            BigDecimal belowNormalAmount,
            BigDecimal belowDiscountedAmount,
            BigDecimal aboveNormalAmount,
            BigDecimal aboveDiscountedAmount,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {}

    public record UpdateThirdPartyRuleRequest(
            @NotBlank @Size(max = 60) String planCode,
            @NotNull @DecimalMin("0.00") BigDecimal fipeThreshold,
            @NotNull @DecimalMin("0.00") BigDecimal belowNormalAmount,
            @NotNull @DecimalMin("0.00") BigDecimal belowDiscountedAmount,
            @NotNull @DecimalMin("0.00") BigDecimal aboveNormalAmount,
            @NotNull @DecimalMin("0.00") BigDecimal aboveDiscountedAmount
    ) {}

    public record UpdateThirdPartyRulesRequest(
            @NotEmpty @Size(max = 10) List<@Valid UpdateThirdPartyRuleRequest> rules
    ) {
        public UpdateThirdPartyRulesRequest {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record CoverageAdminResponse(
            Long id,
            Long coverageId,
            String coverageCode,
            Long planId,
            String planName,
            String category,
            String region,
            MotorcycleOrigin motorcycleOrigin,
            String coverageName,
            CoverageStatus status,
            String detail,
            BigDecimal monthlyPrice,
            Integer sortOrder,
            DamageCondition damageCondition,
            EventCondition eventCondition,
            Integer towingRadiusKm,
            List<CoverageRuleAdminResponse> rules
    ) {}

    public record BenefitAssignmentResponse(
            Long planCoverageId,
            Long planId,
            String planName,
            Long categoryId,
            String category,
            CoverageStatus status,
            String detail,
            BigDecimal monthlyPrice,
            Integer sortOrder,
            DamageCondition damageCondition,
            EventCondition eventCondition,
            Integer towingRadiusKm
    ) {}

    public record BenefitDiscountEffectResponse(
            Long discountRuleId,
            String discountName,
            Integer percent,
            boolean active,
            DiscountBenefitAction action,
            String detailOverride,
            BigDecimal amountOverride
    ) {}

    public record BenefitDiscountEffectRequest(
            @NotNull Long discountRuleId,
            @NotNull DiscountBenefitAction action,
            @Size(max = 240) String detailOverride,
            @DecimalMin("0.00") BigDecimal amountOverride
    ) {}

    public record BenefitAdminResponse(
            Long id,
            String code,
            String name,
            List<Long> optionalVehicleCategoryIds,
            List<String> optionalVehicleCategoryCodes,
            List<CoverageRuleAdminResponse> rules,
            List<BenefitAssignmentResponse> assignments,
            List<BenefitDiscountEffectResponse> discountEffects
    ) {}

    public record BenefitPlanAssignmentRequest(
            @NotNull Long planId,
            @NotNull CoverageStatus status,
            @Size(max = 240) String detail,
            @DecimalMin("0.00") BigDecimal monthlyPrice,
            @NotNull @Min(0) Integer sortOrder,
            DamageCondition damageCondition,
            EventCondition eventCondition,
            @Min(0) Integer towingRadiusKm
    ) {}

    public record CreateBenefitRequest(
            @NotBlank @Size(max = 180) String name,
            @Size(max = 100) List<@NotNull Long> optionalVehicleCategoryIds,
            @Size(max = 500) List<@Valid BenefitPlanAssignmentRequest> assignments,
            @Size(max = 50) List<@Valid CoverageRuleRequest> rules,
            @Size(max = 100) List<@Valid BenefitDiscountEffectRequest> discountEffects
    ) {
        public CreateBenefitRequest {
            optionalVehicleCategoryIds = optionalVehicleCategoryIds == null ? List.of() : List.copyOf(optionalVehicleCategoryIds);
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
            rules = rules == null ? List.of() : List.copyOf(rules);
            discountEffects = discountEffects == null ? List.of() : List.copyOf(discountEffects);
        }
    }

    public record UpdateBenefitRequest(
            @NotBlank @Size(max = 180) String name,
            @Size(max = 100) List<@NotNull Long> optionalVehicleCategoryIds,
            @Size(max = 500) List<@Valid BenefitPlanAssignmentRequest> assignments,
            @Size(max = 50) List<@Valid CoverageRuleRequest> rules,
            @Size(max = 100) List<@Valid BenefitDiscountEffectRequest> discountEffects
    ) {
        public UpdateBenefitRequest {
            // Campos novos podem vir ausentes de uma aba antiga do Admin. Null
            // significa "preservar a configuração atual"; lista vazia significa
            // que o administrador realmente limpou aquela seleção.
            optionalVehicleCategoryIds = optionalVehicleCategoryIds == null ? null : List.copyOf(optionalVehicleCategoryIds);
            assignments = assignments == null ? null : List.copyOf(assignments);
            rules = rules == null ? null : List.copyOf(rules);
            discountEffects = discountEffects == null ? null : List.copyOf(discountEffects);
        }
    }

    public record CreateCoverageRequest(
            @NotEmpty List<@NotNull Long> planIds,
            @NotBlank @Size(max = 180) String coverageName,
            @NotNull CoverageStatus status,
            @Size(max = 240) String detail,
            @DecimalMin("0.00") BigDecimal monthlyPrice,
            @NotNull @Min(0) Integer sortOrder,
            DamageCondition damageCondition,
            EventCondition eventCondition,
            @Min(0) Integer towingRadiusKm,
            @Size(max = 50) List<@Valid CoverageRuleRequest> rules
    ) {
        public CreateCoverageRequest {
            planIds = planIds == null ? List.of() : List.copyOf(planIds);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record UpdateCoverageRequest(
            @NotEmpty List<@NotNull Long> planIds,
            @NotBlank @Size(max = 180) String coverageName,
            @NotNull CoverageStatus status,
            @Size(max = 240) String detail,
            @DecimalMin("0.00") BigDecimal monthlyPrice,
            @NotNull @Min(0) Integer sortOrder,
            DamageCondition damageCondition,
            EventCondition eventCondition,
            @Min(0) Integer towingRadiusKm,
            @Size(max = 50) List<@Valid CoverageRuleRequest> rules
    ) {
        public UpdateCoverageRequest {
            planIds = planIds == null ? List.of() : List.copyOf(planIds);
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record CreatePriceRangeRequest(
            @NotNull Long planId,
            @NotNull PricingRegion pricingRegion,
            @NotNull @DecimalMin("0.00") BigDecimal minValue,
            @NotNull @DecimalMin("0.00") BigDecimal maxValue,
            @NotNull @DecimalMin("0.00") BigDecimal monthlyPrice
    ) {}

    public record UpdatePriceRangeRequest(
            @NotNull PricingRegion pricingRegion,
            @NotNull @DecimalMin("0.00") BigDecimal minValue,
            @NotNull @DecimalMin("0.00") BigDecimal maxValue,
            @NotNull @DecimalMin("0.00") BigDecimal monthlyPrice
    ) {}

    public record UpdatePriceRequest(@NotNull @DecimalMin("0.00") BigDecimal monthlyPrice) {}

    public record UpdatePromotionalMotorcyclePriceRequest(
            @NotBlank @Size(max = 120) String label,
            @NotNull @Min(1) @Max(2500) Integer minCc,
            @NotNull @Min(1) @Max(2500) Integer maxCc,
            @NotNull @DecimalMin("0.00") BigDecimal minFipe,
            @DecimalMin("0.00") BigDecimal maxFipe,
            @NotNull @DecimalMin("0.00") BigDecimal monthlyPrice
    ) {}

    public record UpdateQuoteStatusRequest(
            @NotNull QuoteStatus status,
            @Size(max = 1200) String adminNote
    ) {}

    public record UpdateQuoteConsultantRequest(@NotNull UUID consultantId) {}

    public record UpdateAdminQuoteDetailsRequest(
            @NotBlank @Size(max = 120) String customerName,
            @Size(max = 14) String customerCpf,
            @Size(max = 30) String whatsapp,
            @Size(max = 10) String plate,
            @NotBlank @Size(max = 120) String model,
            @NotNull @Min(1950) @Max(2100) Integer modelYear,
            @NotNull Boolean zeroKm,
            @Size(max = 1200) String observation
    ) {}

    public record UpdateInspectionDetailsRequest(
            @NotBlank @Size(max = 140) String associateName,
            @NotBlank @Size(max = 14) String cpf,
            @Size(max = 30) String whatsapp,
            @Size(max = 10) String plate,
            @NotBlank @Size(max = 120) String model,
            @NotNull @Min(1950) @Max(2100) Integer modelYear,
            @NotNull Boolean zeroKm,
            @Size(max = 600) String residenceAddress
    ) {}

    public record InspectionPlanOption(String code, String name, boolean selected) {}

    public record UpdateInspectionPlanRequest(@NotBlank @Size(max = 80) String planCode) {}

    public record UpdateInspectionContractValuesRequest(
            @NotNull @DecimalMin("0.01") BigDecimal fipeValue,
            @NotNull @DecimalMin("0.01") BigDecimal monthlyValue,
            @NotNull @Min(0) @Max(100) Integer discountPercent,
            @NotNull RearWindowBranding rearWindowBranding,
            @NotNull List<@NotBlank @Size(max = 80) String> benefitCodes,
            Boolean manualMonthlyOverride,
            Long fipeRecordId
    ) {
        public UpdateInspectionContractValuesRequest {
            benefitCodes = benefitCodes == null ? List.of() : List.copyOf(benefitCodes);
            manualMonthlyOverride = Boolean.TRUE.equals(manualMonthlyOverride);
        }
    }

    public record CommercialPricingPreviewRequest(
            @NotNull @DecimalMin("0.01") BigDecimal fipeValue,
            @NotNull @Min(0) @Max(100) Integer discountPercent,
            @NotNull List<@NotBlank @Size(max = 80) String> benefitCodes
    ) {
        public CommercialPricingPreviewRequest {
            benefitCodes = benefitCodes == null ? List.of() : List.copyOf(benefitCodes);
        }
    }

    public record CommercialPricingPreviewResponse(
            BigDecimal planBaseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription,
            BigDecimal optionalsMonthlyValue,
            BigDecimal subtotalBeforeDiscount,
            BigDecimal discountValue,
            BigDecimal finalMonthlyValue,
            boolean catalogBased
    ) {}

    public record CommercialBenefitResponse(
            String code,
            String name,
            CoverageStatus catalogStatus,
            String detail,
            BigDecimal monthlyPrice,
            boolean selected,
            boolean locked,
            String lockReason
    ) {}

    public record CommercialDiscountBenefitEffectResponse(
            String coverageCode,
            DiscountBenefitAction action,
            String detailOverride,
            BigDecimal amountOverride
    ) {}

    public record CommercialDiscountResponse(
            String name,
            Integer percent,
            boolean available,
            String unavailableReason,
            boolean requiresConfirmation,
            RearWindowBranding rearWindowBranding,
            String conditionText,
            boolean applyToBase,
            boolean applyToMandatoryFee,
            boolean applyToOptionals,
            List<CommercialDiscountBenefitEffectResponse> benefitEffects
    ) {}

    public record UpdatePublicQuoteAssignmentSettingsRequest(@NotNull Boolean enabled) {}

    public record PublicQuoteAssignmentSettingsResponse(
            boolean enabled,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {}

    public record UpdateInspectionStatusRequest(
            @NotNull InspectionRequestStatus status,
            @Size(max = 1200) String adminNote,
            UUID analystId,
            @Size(max = 140) String analystName
    ) {}

    public record AdminQuoteResponse(
            UUID id,
            String quoteNumber,
            QuoteOrigin origin,
            UUID consultantId,
            String consultantName,
            String customerName,
            String maskedCpf,
            String customerCpf,
            String whatsapp,
            String plate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            boolean detailsEditable,
            BigDecimal fipeValue,
            Boolean auctionOrChassisRemarked,
            Integer indemnityFipePercent,
            String categoryCode,
            Region region,
            MotorcycleOrigin motorcycleOrigin,
            Integer motorcycleCc,
            String observation,
            String selectedPlanName,
            BigDecimal preDiscountMonthlyValue,
            Integer discountPercent,
            RearWindowBranding rearWindowBranding,
            BigDecimal monthlyValue,
            BigDecimal oneTimeFee,
            QuoteStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime validUntil,
            boolean expired,
            OffsetDateTime decidedAt,
            String adminNote,
            OffsetDateTime reviewedAt,
            String pdfUrl,
            String driveFolderUrl,
            String drivePdfUrl,
            String inspectionUrl,
            String teamWhatsappUrl,
            String teamEmailUrl
    ) {}

    public record AdminInspectionResponse(
            UUID id,
            String requestType,
            String vehicleType,
            String associateName,
            String maskedCpf,
            String cpf,
            String whatsapp,
            String plate,
            boolean zeroKm,
            String vehicleModel,
            Integer modelYear,
            String vehicleFipeCode,
            String vehicleFipeReference,
            String residenceAddress,
            boolean preexistingDamage,
            String preexistingDamageDetails,
            String contractedPlan,
            BigDecimal fipeValue,
            BigDecimal monthlyValue,
            BigDecimal preDiscountMonthlyValue,
            Integer billingDueDay,
            LocalDate firstBillingDueDate,
            Integer discountPercent,
            RearWindowBranding rearWindowBranding,
            String selectedPlanName,
            String selectedPlanCode,
            List<CommercialDiscountResponse> commercialDiscounts,
            List<CommercialBenefitResponse> commercialBenefits,
            List<ThirdPartyRuleSettingsResponse> thirdPartyRules,
            boolean contractChangePending,
            Integer pendingDiscountPercent,
            RearWindowBranding pendingRearWindowBranding,
            List<String> pendingBenefitCodes,
            BigDecimal pendingFipeValue,
            BigDecimal pendingMonthlyValue,
            OffsetDateTime contractChangeRequestedAt,
            String contractChangeConfirmationUrl,
            String contractChangeWhatsappUrl,
            String signatureUrl,
            UUID consultantId,
            String consultantName,
            UUID assignedAnalystId,
            String assignedAnalystName,
            InspectionAnalysisStage analysisStage,
            OffsetDateTime registrationCompletedAt,
            String registrationCompletedByName,
            InspectionRequestStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime expiresAt,
            boolean expiredWithoutFiles,
            OffsetDateTime completedAt,
            String adminNote,
            String supervisionNote,
            OffsetDateTime supervisionNoteUpdatedAt,
            String supervisionNoteByName,
            OffsetDateTime reviewedAt,
            UUID reviewedByCollaboratorId,
            String reviewedByName,
            String reviewedByRole,
            String publicUrl,
            String driveFolderUrl,
            String reportUrl,
            String quotationPdfUrl,
            String teamWhatsappUrl,
            String teamEmailUrl,
            String associateInspectionWhatsappUrl,
            String consultantInspectionWhatsappUrl,
            String associateDecisionWhatsappUrl,
            OffsetDateTime decisionMessageSentAt,
            boolean associateDecisionMessagePending,
            int assetCount,
            int expiredAssetCount,
            OffsetDateTime filesExpireAt,
            OffsetDateTime digitalAcceptedAt,
            String digitalAcceptanceProofHash,
            boolean digitalAcceptanceUserVerified,
            String digitalAcceptanceWhatsappSentTo,
            String digitalAcceptanceDeviceId,
            Double digitalAcceptanceLatitude,
            Double digitalAcceptanceLongitude,
            Double digitalAcceptanceAccuracyMeters,
            OffsetDateTime digitalAcceptanceLocationCapturedAt,
            boolean dossierAcceptanceCompleted,
            boolean responsibilityAcceptanceCompleted,
            boolean partsDelayAcceptanceCompleted,
            boolean allThreeAcceptancesCompleted,
            boolean acceptanceReapprovalRequired,
            int acceptanceCompletedCount,
            List<InspectionDtos.InspectionAssetResponse> assets
    ) {}

    public record CommunicationSettingsResponse(
            String teamEmail,
            String teamWhatsapp,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {}

    public record UpdateCommunicationSettingsRequest(
            @Email @Size(max = 180) String teamEmail,
            @Pattern(regexp = "^$|^[0-9]{10,15}$", message = "Informe o WhatsApp somente com números, incluindo DDI e DDD.") String teamWhatsapp
    ) {}

    public record DeleteSummary(
            int deleted,
            int protectedApproved,
            String message
    ) {}

    public record AuditResponse(
            Long id,
            String itemType,
            Long itemId,
            String itemKey,
            String description,
            BigDecimal oldValue,
            BigDecimal newValue,
            String oldText,
            String newText,
            String changedBy,
            OffsetDateTime changedAt
    ) {}
}
