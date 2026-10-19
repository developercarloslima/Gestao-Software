package br.com.nh.cotacao.dto;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ProcurementDtos {
    private ProcurementDtos() {}

    public record PurchaseEventSummary(
            UUID id,
            String protocol,
            String associateName,
            String associateNumber,
            String vehiclePlate,
            String vehicleBrand,
            String vehicleModel,
            String vehicleCategory,
            String planName,
            OffsetDateTime workshopCompletedAt,
            String workshopCompletedBy,
            int totalItems,
            int unfilledItems,
            int requestedItems,
            int finalizedItems,
            String bucket
    ) {}

    public record PurchaseItem(
            UUID id,
            UUID checklistItemId,
            UUID thirdPartyId,
            String itemLabel,
            String supplier,
            BigDecimal amount,
            LocalDate deliveryDeadline,
            String status,
            String notes,
            String updatedBy,
            OffsetDateTime updatedAt,
            OffsetDateTime detailsSavedAt,
            String detailsSavedBy
    ) {}

    public record PurchaseEventDetail(
            UUID id,
            String protocol,
            String associateName,
            String associateNumber,
            String vehiclePlate,
            String vehicleBrand,
            String vehicleModel,
            String vehicleCategory,
            String planName,
            OffsetDateTime workshopCompletedAt,
            String workshopCompletedBy,
            int totalItems,
            int unfilledItems,
            int requestedItems,
            int finalizedItems,
            String bucket,
            List<PurchaseItem> purchases
    ) {}

    public record UpdatePurchaseRequest(
            @Size(max = 180) String supplier,
            BigDecimal amount,
            LocalDate deliveryDeadline,
            String status,
            @Size(max = 3000) String notes
    ) {}
}
