package br.com.nh.cotacao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TowPortalDtos {
    private TowPortalDtos() {}

    public record TowQueueSummary(
            UUID id,
            String code,
            String vehiclePlate,
            String vehicleModel,
            String vehicleCategory,
            String providerName,
            String driverName,
            String status,
            int answeredItems,
            int totalItems,
            int photoCount,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt,
            String completedByName
    ) {}

    public record TowTemplateItem(
            UUID id,
            String section,
            String label,
            int sortOrder
    ) {}

    public record TowItem(
            UUID id,
            UUID templateItemId,
            String section,
            String label,
            int sortOrder,
            String answer,
            String notes,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {}

    public record TowPhoto(
            UUID id,
            UUID checklistItemId,
            String photoKind,
            String originalName,
            String contentType,
            long fileSize,
            String notes,
            String uploadedBy,
            OffsetDateTime createdAt
    ) {}

    public record TowRecordDetail(
            UUID id,
            String code,
            String vehiclePlate,
            String vehicleModel,
            String vehicleCategory,
            String providerName,
            String driverName,
            String driverPhone,
            String generalNotes,
            String status,
            String createdByName,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime completedAt,
            String completedByName,
            int answeredItems,
            int totalItems,
            int photoCount,
            List<TowItem> checklist,
            List<TowPhoto> photos,
            boolean canEdit
    ) {}

    public record InitialTowAnswer(
            UUID templateItemId,
            @NotBlank String answer,
            @Size(max=3000) String notes
    ) {}

    public record CreateTowRecordRequest(
            @NotBlank @Size(max=20) String vehiclePlate,
            @Size(max=180) String vehicleModel,
            String vehicleCategory,
            @Size(max=180) String providerName,
            @Size(max=180) String driverName,
            @Size(max=40) String driverPhone,
            @Size(max=3000) String generalNotes,
            List<InitialTowAnswer> checklist
    ) {}

    public record UpdateTowItemRequest(
            @NotBlank String answer,
            @Size(max = 3000) String notes
    ) {}

    public record TowPhotoContent(String fileName,String contentType,byte[] bytes) {}
}
