package br.com.nh.cotacao.dto;

import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.entity.InspectionRequestType;
import br.com.nh.cotacao.entity.QuoteStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ConsultantDashboardDtos {
    private ConsultantDashboardDtos() {}

    public record ConsultantQuoteSummary(
            UUID id,
            String quoteNumber,
            String customerName,
            String customerCpf,
            String whatsapp,
            String plate,
            boolean zeroKm,
            String model,
            Integer manufactureYear,
            String categoryCode,
            Integer motorcycleCc,
            String observation,
            String selectedPlanName,
            BigDecimal monthlyValue,
            QuoteStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime validUntil,
            boolean expired,
            boolean hasCustomerCpf,
            String pdfUrl,
            UUID inspectionId,
            InspectionRequestStatus inspectionStatus,
            OffsetDateTime inspectionCompletedAt,
            String inspectionPublicUrl,
            String inspectionWhatsappUrl,
            String inspectionDriveFolderUrl,
            boolean inspectionHasFiles,
            int inspectionAssetCount
    ) {}

    public record ConsultantInspectionSummary(
            UUID id,
            InspectionRequestType requestType,
            String associateName,
            String plate,
            InspectionRequestStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            boolean expiredWithoutFiles,
            OffsetDateTime completedAt,
            String reportUrl,
            String whatsapp,
            String associateCompletionWhatsappUrl,
            OffsetDateTime completionMessageSentAt,
            boolean completionMessagePending,
            String publicUrl,
            String associateInspectionWhatsappUrl,
            String driveFolderUrl,
            boolean hasFiles,
            int assetCount,
            OffsetDateTime filesExpireAt,
            List<InspectionDtos.InspectionAssetResponse> assets
    ) {}


    public record StartInspectionRequest(
            @Pattern(regexp = "^[0-9.\\-]{11,14}$", message = "CPF inválido") String cpf
    ) {}

    public record RedoQuoteRequest(
            @Pattern(regexp = "^[0-9.\\-]{11,14}$", message = "CPF inválido") String cpf
    ) {}

    public record UpdateQuoteDetailsRequest(
            @NotBlank @Size(max = 120) String customerName,
            @Pattern(regexp = "^$|^[0-9.\\-]{11,14}$", message = "CPF inválido") String cpf,
            @Size(max = 30) String whatsapp,
            @Size(max = 10) String plate,
            @NotBlank @Size(max = 120) String model,
            @NotNull @Min(1950) @Max(2100) Integer manufactureYear,
            @NotNull Boolean zeroKm,
            @Size(max = 1200) String observation
    ) {}

    public record ConsultantQuoteDecisionRequest(
            @NotNull QuoteStatus decision
    ) {}

    public record ConsultantDashboardResponse(
            UUID consultantId,
            String consultantName,
            List<ConsultantQuoteSummary> quotes,
            List<ConsultantInspectionSummary> inspections
    ) {}
}
