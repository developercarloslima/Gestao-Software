package br.com.nh.cotacao.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChecklistEventDtos {
    private ChecklistEventDtos() {}

    public record TemplateItem(
            UUID id,
            String vehicleCategory,
            String section,
            String label,
            int sortOrder
    ) {}

    public record TowTemplateItem(
            UUID id,
            String section,
            String label,
            int sortOrder
    ) {}

    public record DocumentRequirement(
            String eventType,
            String attachmentKind,
            String label,
            boolean required,
            int sortOrder
    ) {}

    public record MetaResponse(
            Map<String, String> eventTypes,
            Map<String, String> vehicleCategories,
            Map<String, String> statuses,
            Map<String, String> reportedStates,
            Map<String, String> analysisStates,
            Map<String, String> towAnswers,
            Map<String, String> attachmentKinds,
            Map<String, List<TemplateItem>> templates,
            List<TowTemplateItem> towTemplate,
            Map<String, List<DocumentRequirement>> documentRequirements,
            long maxUploadBytes
    ) {}

    public record ChecklistAnswerRequest(
            @NotNull UUID templateItemId,
            @NotBlank String state,
            @Size(max = 3000) String notes
    ) {}

    public record TowAnswerRequest(
            @NotNull UUID templateItemId,
            @NotBlank String answer,
            @Size(max = 3000) String notes
    ) {}

    public record ThirdPartyCreateRequest(
            @NotBlank @Size(max = 180) String name,
            @NotBlank @Size(max = 60) String document,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 180) String vehicle,
            @NotBlank @Size(max = 20) String vehiclePlate,
            @NotBlank @Size(max = 180) String vehicleModel,
            @NotBlank @Size(max = 80) String vehicleColor,
            @NotBlank String vehicleCategory,
            @Size(max = 3000) String notes,
            @Valid List<ChecklistAnswerRequest> checklist
    ) {}

    public record CreateEventRequest(
            @NotBlank String eventType,
            OffsetDateTime occurredAt,
            @Size(max = 260) String location,
            @Size(max = 8000) String description,
            @NotBlank @Size(max = 180) String associateName,
            @NotBlank @Size(max = 80) String associateNumber,
            @NotBlank @Size(max = 140) String planName,
            @Size(max = 80) String planCode,
            BigDecimal planMonthlyAmount,
            BigDecimal participationAmount,
            @Size(max = 5000) String coverageNotes,
            @NotBlank @Size(max = 20) String vehiclePlate,
            @NotBlank @Size(max = 120) String vehicleBrand,
            @NotBlank @Size(max = 180) String vehicleModel,
            @Size(max = 180) String vehicleVersion,
            @NotBlank String vehicleCategory,
            @Size(max = 80) String vehicleSubtype,
            @Size(max = 20) String vehicleYear,
            @Size(max = 80) String vehicleColor,
            @Size(max = 60) String vehicleFuel,
            @Size(max = 60) String vehicleTransmission,
            Long vehicleOdometer,
            @Size(max = 40) String fipeCode,
            BigDecimal fipeValue,
            @Size(max = 80) String chassis,
            boolean hasThirdParty,
            boolean towService,
            UUID responsibleConsultantId,
            @Valid List<ChecklistAnswerRequest> checklist,
            @Valid List<TowAnswerRequest> towChecklist,
            @Valid List<ThirdPartyCreateRequest> thirdParties
    ) {}

    public record UpdateEventRequest(
            OffsetDateTime occurredAt,
            @Size(max = 260) String location,
            @Size(max = 8000) String description,
            @Size(max = 180) String associateName,
            @Size(max = 80) String associateNumber,
            @Size(max = 140) String planName,
            @Size(max = 80) String planCode,
            BigDecimal planMonthlyAmount,
            BigDecimal participationAmount,
            @Size(max = 5000) String coverageNotes,
            @Size(max = 20) String vehiclePlate,
            @Size(max = 120) String vehicleBrand,
            @Size(max = 180) String vehicleModel,
            @Size(max = 180) String vehicleVersion,
            String vehicleCategory,
            @Size(max = 80) String vehicleSubtype,
            @Size(max = 20) String vehicleYear,
            @Size(max = 80) String vehicleColor,
            @Size(max = 60) String vehicleFuel,
            @Size(max = 60) String vehicleTransmission,
            Long vehicleOdometer,
            @Size(max = 40) String fipeCode,
            BigDecimal fipeValue,
            @Size(max = 80) String chassis,
            @Size(max = 5000) String pendingReason,
            @Size(max = 5000) String managerNotes
    ) {}

    public record UpdateChecklistItemRequest(
            String reportedState,
            @Size(max = 3000) String reportedNotes,
            String analysisState,
            @Size(max = 3000) String analysisNotes
    ) {}

    public record UpdateTowItemRequest(
            @NotBlank String answer,
            @Size(max = 3000) String notes
    ) {}

    public record ReviewStatusRequest(
            @NotBlank String status,
            @Size(max = 5000) String pendingReason,
            @Size(max = 5000) String managerNotes
    ) {}

    public record EventSummary(
            UUID id,
            String protocol,
            String eventType,
            String status,
            String associateName,
            String associateNumber,
            String planName,
            String vehiclePlate,
            String vehicleModel,
            String vehicleCategory,
            boolean towService,
            boolean hasThirdParty,
            String createdByName,
            OffsetDateTime occurredAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record ChecklistItemResponse(
            UUID id,
            UUID thirdPartyId,
            UUID templateItemId,
            String section,
            String label,
            int sortOrder,
            String reportedState,
            String reportedNotes,
            String analysisState,
            String analysisNotes,
            String updatedBy,
            OffsetDateTime updatedAt
    ) {}

    public record TowItemResponse(
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

    public record ThirdPartyResponse(
            UUID id,
            String name,
            String document,
            String phone,
            String vehicle,
            String vehiclePlate,
            String vehicleModel,
            String vehicleColor,
            String vehicleCategory,
            String notes,
            OffsetDateTime createdAt,
            List<ChecklistItemResponse> checklist
    ) {}

    public record AttachmentResponse(
            UUID id,
            UUID thirdPartyId,
            UUID checklistItemId,
            String context,
            String attachmentKind,
            String originalName,
            String contentType,
            long fileSize,
            String notes,
            String uploadedBy,
            OffsetDateTime createdAt
    ) {}

    public record AuditLogResponse(
            UUID id,
            String actorUsername,
            String actorName,
            String action,
            String entityType,
            UUID entityId,
            String details,
            OffsetDateTime createdAt
    ) {}

    public record EventDetail(
            UUID id,
            String protocol,
            String eventType,
            String status,
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
            boolean hasThirdParty,
            boolean towService,
            String pendingReason,
            String managerNotes,
            String createdByUsername,
            String createdByName,
            UUID createdByCollaboratorId,
            String lastUpdatedBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime registrationCompletedAt,
            OffsetDateTime analysisCompletedAt,
            OffsetDateTime finalizedAt,
            List<ChecklistItemResponse> checklist,
            List<TowItemResponse> towChecklist,
            List<ThirdPartyResponse> thirdParties,
            List<AttachmentResponse> attachments,
            List<DocumentRequirement> requirements,
            List<AuditLogResponse> audit,
            boolean canEditRegistration,
            boolean canAnalyze,
            boolean canFinalize
    ) {}


    public record TowLookupItem(
            String label,
            String answer,
            String notes
    ) {}

    public record TowLookupResponse(
            UUID id,
            String code,
            String vehiclePlate,
            String vehicleModel,
            String vehicleCategory,
            String providerName,
            String driverName,
            String generalNotes,
            OffsetDateTime completedAt,
            int photoCount,
            List<TowLookupItem> checklist
    ) {}

    public record AttachmentContent(
            String fileName,
            String contentType,
            byte[] bytes
    ) {}
}
