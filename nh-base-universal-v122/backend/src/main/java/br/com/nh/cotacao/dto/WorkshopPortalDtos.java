package br.com.nh.cotacao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkshopPortalDtos {
    private WorkshopPortalDtos() {}

    public record WorkshopQueueSummary(
            UUID id,
            String protocol,
            String eventType,
            String eventStatus,
            String associateName,
            String associateNumber,
            String vehiclePlate,
            String vehicleModel,
            String vehicleCategory,
            OffsetDateTime occurredAt,
            OffsetDateTime createdAt,
            OffsetDateTime workshopStartedAt,
            OffsetDateTime workshopCompletedAt,
            int assessedItems,
            int totalItems,
            int damagedItems,
            int replaceItems,
            int purchaseItems,
            OffsetDateTime acceptedAt,
            String publicAcceptanceToken
    ) {}

    public record WorkshopItem(
            UUID id,
            UUID thirdPartyId,
            String section,
            String label,
            int sortOrder,
            String damageState,
            String repairAction,
            String notes,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {}

    public record WorkshopThirdParty(
            UUID id,
            String name,
            String vehicle,
            String vehiclePlate,
            String vehicleModel,
            String vehicleColor,
            String vehicleCategory,
            List<WorkshopItem> checklist
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

    public record WorkshopEventDetail(
            UUID id,
            String protocol,
            String eventType,
            String eventStatus,
            OffsetDateTime occurredAt,
            String location,
            String description,
            String associateName,
            String associateNumber,
            String planName,
            String planCode,
            BigDecimal planMonthlyAmount,
            BigDecimal participationAmount,
            String coverageNotes,
            String vehiclePlate,
            String vehicleBrand,
            String vehicleModel,
            String vehicleVersion,
            String vehicleCategory,
            String vehicleSubtype,
            String vehicleYear,
            String vehicleColor,
            String vehicleFuel,
            String vehicleTransmission,
            Long vehicleOdometer,
            String fipeCode,
            BigDecimal fipeValue,
            String chassis,
            OffsetDateTime createdAt,
            OffsetDateTime workshopStartedAt,
            String workshopStartedBy,
            OffsetDateTime workshopCompletedAt,
            String workshopCompletedBy,
            OffsetDateTime acceptedAt,
            String publicAcceptanceToken,
            List<WorkshopItem> checklist,
            List<WorkshopThirdParty> thirdParties,
            List<PurchaseItem> purchases,
            boolean purchasesAvailable,
            boolean canEdit
    ) {}

    public record UpdateWorkshopItemRequest(
            @NotBlank String damageState,
            String repairAction,
            @Size(max = 3000) String notes
    ) {}

    public record AddCustomWorkshopItemRequest(
            @NotBlank @Size(max = 180) String label,
            UUID thirdPartyId
    ) {}

    public record BulkNoDamageRequest(
            String section,
            UUID thirdPartyId
    ) {}

    public record UpdatePurchaseRequest(
            @Size(max = 180) String supplier,
            BigDecimal amount,
            LocalDate deliveryDeadline,
            String status,
            @Size(max = 3000) String notes
    ) {}
}
