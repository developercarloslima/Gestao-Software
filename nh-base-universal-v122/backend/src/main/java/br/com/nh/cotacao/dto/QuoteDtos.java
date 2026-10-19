package br.com.nh.cotacao.dto;

import br.com.nh.cotacao.entity.CoverageStatus;
import br.com.nh.cotacao.entity.AdhesionPaymentMethod;
import br.com.nh.cotacao.entity.DiscountBenefitAction;
import br.com.nh.cotacao.entity.MotorcycleOrigin;
import br.com.nh.cotacao.entity.QuoteOrigin;
import br.com.nh.cotacao.entity.PricingRegion;
import br.com.nh.cotacao.entity.RearWindowBranding;
import br.com.nh.cotacao.entity.QuoteStatus;
import br.com.nh.cotacao.entity.Region;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class QuoteDtos {
    private QuoteDtos() {
    }

    public record OptionsRequest(
            @NotBlank String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            @NotNull @DecimalMin("0.01") BigDecimal fipeValue,
            Boolean motorcycle,
            @Min(1) @Max(2500) Integer motorcycleCc,
            @Size(max = 40) String promoMotorcycleTier,
            @Min(1950) @Max(2100) Integer manufactureYear
    ) {
        public PricingRegion effectivePricingRegion() {
            return pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
        }

        public MotorcycleOrigin effectiveMotorcycleOrigin() {
            boolean motorcycleCategory = categoryCode != null && categoryCode.startsWith("MOTORCYCLE");
            boolean promotional = "MOTORCYCLE_PROMO_2026".equals(categoryCode);
            if (!motorcycleCategory || promotional) return null;
            if (motorcycleOrigin != null) return motorcycleOrigin;
            if (effectivePricingRegion() == PricingRegion.OUTSIDE_NORTHEAST) return MotorcycleOrigin.OUTSIDE_NORTHEAST;
            if (region == Region.CAPITAL) return MotorcycleOrigin.CAPITAL;
            return MotorcycleOrigin.NORTHEAST;
        }
    }

    public record CoverageOption(
            String code,
            String name,
            CoverageStatus status,
            String detail,
            String discountedDetail,
            BigDecimal monthlyPrice
    ) {
    }

    public record DiscountBenefitEffect(
            String coverageCode,
            DiscountBenefitAction action,
            String detailOverride,
            BigDecimal amountOverride
    ) {}

    public record DiscountOption(
            Long id,
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
            List<DiscountBenefitEffect> benefitEffects
    ) {}

    public record PlanOption(
            String code,
            String name,
            String subtitle,
            BigDecimal tableMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal monthlyValue,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription,
            List<CoverageOption> coverages,
            List<DiscountOption> discounts
    ) {
    }

    public record OptionsResponse(
            String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            BigDecimal fipeValue,
            List<PlanOption> plans
    ) {
    }

    public record CreateQuoteRequest(
            @NotNull UUID consultantId,
            @NotBlank @Size(max = 120) String customerName,
            @Pattern(regexp = "^(?:[0-9.\\-]{11,14})?$", message = "CPF inválido") String cpf,
            @Size(max = 30) String whatsapp,
            @Size(max = 10) String plate,
            @NotBlank @Size(max = 120) String model,
            @NotNull @Min(1950) @Max(2100) Integer manufactureYear,
            @NotNull Boolean zeroKm,
            @NotNull @DecimalMin("0.01") BigDecimal fipeValue,
            Boolean auctionOrChassisRemarked,
            @NotBlank String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            Boolean motorcycle,
            @Min(1) @Max(2500) Integer motorcycleCc,
            @Size(max = 40) String promoMotorcycleTier,
            @Size(max = 1200) String observation,
            @NotNull LocalDate firstBillingDueDate,
            @NotBlank String selectedPlanCode,
            @Size(max = 20) List<@NotBlank String> selectedOptionalCodes,
            @Min(0) @Max(100) Integer discountPercent,
            RearWindowBranding rearWindowBranding,
            @Pattern(regexp = "^(?:[0-9]{8})?$", message = "CEP deve possuir 8 dígitos") String residenceCep,
            @Size(max = 600) String residenceAddress,
            UUID associateId,
            AdhesionPaymentMethod adhesionPaymentMethod
    ) {
        public CreateQuoteRequest {
            selectedOptionalCodes = selectedOptionalCodes == null ? List.of() : List.copyOf(selectedOptionalCodes);
        }

        public PricingRegion effectivePricingRegion() {
            return pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
        }

        public MotorcycleOrigin effectiveMotorcycleOrigin() {
            boolean motorcycleCategory = categoryCode != null && categoryCode.startsWith("MOTORCYCLE");
            boolean promotional = "MOTORCYCLE_PROMO_2026".equals(categoryCode);
            if (!motorcycleCategory || promotional) return null;
            if (motorcycleOrigin != null) return motorcycleOrigin;
            if (effectivePricingRegion() == PricingRegion.OUTSIDE_NORTHEAST) return MotorcycleOrigin.OUTSIDE_NORTHEAST;
            if (region == Region.CAPITAL) return MotorcycleOrigin.CAPITAL;
            return MotorcycleOrigin.NORTHEAST;
        }
    }

    public record CreatePublicQuoteRequest(
            @NotBlank @Size(max = 120) String customerName,
            @NotBlank @Size(max = 30) String whatsapp,
            @Pattern(regexp = "^(?:[0-9.\\-]{11,14})?$", message = "CPF inválido") String cpf,
            @Size(max = 10) String plate,
            @NotBlank @Size(max = 120) String model,
            @NotNull @Min(1950) @Max(2100) Integer manufactureYear,
            @NotNull Boolean zeroKm,
            @NotNull @DecimalMin("0.01") BigDecimal fipeValue,
            Boolean auctionOrChassisRemarked,
            @NotBlank String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            Boolean motorcycle,
            @Min(1) @Max(2500) Integer motorcycleCc,
            @Size(max = 40) String promoMotorcycleTier,
            @Size(max = 1200) String observation,
            @NotNull LocalDate firstBillingDueDate,
            @NotBlank String selectedPlanCode,
            @Size(max = 20) List<@NotBlank String> selectedOptionalCodes,
            @Pattern(regexp = "^(?:[0-9]{8})?$", message = "CEP deve possuir 8 dígitos") String residenceCep,
            @Size(max = 600) String residenceAddress
    ) {
        public CreatePublicQuoteRequest {
            selectedOptionalCodes = selectedOptionalCodes == null ? List.of() : List.copyOf(selectedOptionalCodes);
        }

        public PricingRegion effectivePricingRegion() {
            return pricingRegion == null ? PricingRegion.NORTHEAST : pricingRegion;
        }

        public MotorcycleOrigin effectiveMotorcycleOrigin() {
            boolean motorcycleCategory = categoryCode != null && categoryCode.startsWith("MOTORCYCLE");
            boolean promotional = "MOTORCYCLE_PROMO_2026".equals(categoryCode);
            if (!motorcycleCategory || promotional) return null;
            if (motorcycleOrigin != null) return motorcycleOrigin;
            if (effectivePricingRegion() == PricingRegion.OUTSIDE_NORTHEAST) return MotorcycleOrigin.OUTSIDE_NORTHEAST;
            if (region == Region.CAPITAL) return MotorcycleOrigin.CAPITAL;
            return MotorcycleOrigin.NORTHEAST;
        }
    }

    public record SelectedOptionalResponse(
            String code,
            String name,
            String detail,
            BigDecimal monthlyPrice
    ) {
    }

    public record InspectionPhotoResponse(
            UUID id,
            String label,
            String fileName,
            String driveFileUrl,
            Integer sortOrder
    ) {
    }

    public record QuoteResponse(
            UUID id,
            String quoteNumber,
            QuoteOrigin origin,
            UUID consultantId,
            String consultantName,
            String customerName,
            String maskedCpf,
            String whatsapp,
            String plate,
            String model,
            Integer manufactureYear,
            boolean zeroKm,
            BigDecimal fipeValue,
            Boolean auctionOrChassisRemarked,
            Integer indemnityFipePercent,
            String categoryCode,
            Region region,
            PricingRegion pricingRegion,
            MotorcycleOrigin motorcycleOrigin,
            boolean motorcycle,
            Integer motorcycleCc,
            String observation,
            String residenceCep,
            String residenceAddress,
            Integer billingDueDay,
            LocalDate firstBillingDueDate,
            String selectedPlanCode,
            String selectedPlanName,
            BigDecimal baseMonthlyValue,
            BigDecimal mandatoryMonthlyFee,
            BigDecimal optionalMonthlyValue,
            BigDecimal preDiscountMonthlyValue,
            Integer discountPercent,
            RearWindowBranding rearWindowBranding,
            BigDecimal monthlyValue,
            BigDecimal oneTimeFee,
            String mandatoryFeeDescription,
            List<SelectedOptionalResponse> selectedOptionals,
            QuoteStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime validUntil,
            boolean expired,
            OffsetDateTime decidedAt,
            String driveFolderUrl,
            String drivePdfUrl,
            OffsetDateTime inspectionCompletedAt,
            List<InspectionPhotoResponse> inspectionPhotos,
            String teamWhatsappUrl,
            String clientWhatsappUrl,
            String inspectionUrl,
            String selfServiceWhatsappUrl
    ) {
    }

    public record DecisionRequest(@NotNull QuoteStatus decision) {
    }

    public record DecisionResponse(
            QuoteResponse quote,
            String inspectionUrl,
            String whatsappUrl
    ) {
    }

    public record InspectionUploadResponse(
            QuoteResponse quote,
            String driveFolderUrl,
            String drivePdfUrl,
            String pdfUrl,
            String teamWhatsappUrl,
            String clientWhatsappUrl
    ) {
    }
}
