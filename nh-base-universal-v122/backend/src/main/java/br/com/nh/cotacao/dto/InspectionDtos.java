package br.com.nh.cotacao.dto;

import br.com.nh.cotacao.entity.InspectionAssetType;
import br.com.nh.cotacao.entity.InspectionRequestStatus;
import br.com.nh.cotacao.entity.InspectionRequestType;
import br.com.nh.cotacao.entity.InspectionVehicleType;
import br.com.nh.cotacao.entity.RearWindowBranding;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class InspectionDtos {
    private InspectionDtos() {}

    public record CreateInspectionRequest(
            @NotNull UUID consultantId,
            @NotNull InspectionRequestType requestType,
            InspectionVehicleType vehicleType,
            @NotBlank @Size(max = 140) String associateName,
            @NotBlank @Pattern(regexp = "^[0-9.\\-]{11,14}$", message = "CPF inválido") String cpf,
            @Size(max = 30) String whatsapp,
            @Size(max = 10) String plate,
            boolean zeroKm,
            @Size(max = 160) String contractedPlan
    ) {}

    public record InspectionAssetResponse(
            UUID id,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            String driveFileUrl,
            int sortOrder,
            boolean available,
            OffsetDateTime storedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime purgedAt
    ) {}

    public record DigitalAcceptanceStatusResponse(
            boolean required,
            boolean eligible,
            boolean accepted,
            OffsetDateTime acceptedAt,
            String evidenceHash,
            String proofHash,
            String dossierSha256,
            String selfieSha256,
            boolean userVerified
    ) {}

    public record DeviceMetadata(
            @Size(max = 96) String deviceId,
            @Size(max = 1200) String userAgent,
            @Size(max = 160) String platform,
            @Size(max = 160) String vendor,
            @Size(max = 40) String language,
            @Size(max = 400) String languages,
            @Size(max = 120) String timezone,
            Integer screenWidth,
            Integer screenHeight,
            Integer colorDepth,
            Double pixelRatio,
            Integer touchPoints,
            Integer hardwareConcurrency,
            Double deviceMemory,
            Boolean cookieEnabled,
            Boolean online,
            Boolean webdriver,
            Boolean webauthnAvailable,
            Boolean platformAuthenticatorAvailable,
            @Size(max = 600) String currentUrl,
            @Size(max = 600) String referrer,
            Double latitude,
            Double longitude,
            Double accuracyMeters,
            @Size(max = 80) String capturedAt
    ) {}

    public record WebAuthnRegistrationOptionsResponse(
            String challenge,
            String rpId,
            String rpName,
            String userId,
            String userName,
            String userDisplayName,
            long timeoutMs
    ) {}

    public record WebAuthnRegistrationFinishRequest(
            @NotBlank @Size(max = 2048) String id,
            @NotBlank @Size(max = 2048) String rawId,
            @NotBlank @Size(max = 80) String type,
            @NotBlank @Size(max = 12000) String clientDataJSON,
            @NotBlank @Size(max = 30000) String attestationObject,
            @NotNull @Valid DeviceMetadata device
    ) {}

    public record WebAuthnAssertionOptionsResponse(
            String challenge,
            String rpId,
            String credentialId,
            long timeoutMs,
            String evidenceHash,
            String dossierSha256,
            String selfieSha256
    ) {}

    public record WebAuthnAssertionFinishRequest(
            @NotBlank @Size(max = 2048) String id,
            @NotBlank @Size(max = 2048) String rawId,
            @NotBlank @Size(max = 80) String type,
            @NotBlank @Size(max = 12000) String clientDataJSON,
            @NotBlank @Size(max = 12000) String authenticatorData,
            @NotBlank @Size(max = 12000) String signature,
            @Size(max = 12000) String userHandle
    ) {}

    public record InspectionResponse(
            UUID id,
            String publicToken,
            InspectionRequestType requestType,
            InspectionVehicleType vehicleType,
            String associateName,
            String maskedCpf,
            String whatsapp,
            String plate,
            String vehicleModel,
            Integer modelYear,
            String residenceAddress,
            boolean preexistingDamage,
            String preexistingDamageDetails,
            String contractedPlan,
            Integer discountPercent,
            RearWindowBranding rearWindowBranding,
            UUID consultantId,
            String consultantName,
            InspectionRequestStatus status,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            OffsetDateTime completedAt,
            String publicUrl,
            String whatsappUrl,
            String teamWhatsappUrl,
            String associateCompletionWhatsappUrl,
            String driveFolderUrl,
            String reportUrl,
            DigitalAcceptanceStatusResponse digitalAcceptance,
            List<InspectionAssetResponse> assets
    ) {}

    public record InspectionUploadResponse(
            InspectionResponse inspection,
            String driveFolderUrl,
            String reportUrl,
            boolean automaticWhatsappSent,
            String automaticWhatsappDetail
    ) {}

    public record ChunkUploadResponse(
            boolean complete,
            int receivedChunks,
            int totalChunks,
            InspectionAssetType assetType,
            int sortOrder,
            InspectionResponse inspection
    ) {}

    public record FinishInspectionUploadRequest(
            String residenceAddress,
            Boolean preexistingDamage,
            @Size(max = 4000) String preexistingDamageDetails
    ) {}

    public record ChunkUploadStatusResponse(
            boolean complete,
            List<Integer> receivedChunks,
            InspectionResponse inspection
    ) {}
}
