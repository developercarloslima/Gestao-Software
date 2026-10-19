package br.com.nh.cotacao.dto;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class ResponsibilityTermDtos {
    private ResponsibilityTermDtos() {}

    public record PrepareResponsibilityTermRequest(
            @Size(max = 4000) String damageDetails
    ) {}

    public record ResponsibilityTermInfo(
            UUID termId,
            UUID inspectionId,
            int version,
            String associateName,
            String vehiclePlate,
            String vehicleModel,
            String damageDetails,
            boolean eligible,
            boolean accepted,
            OffsetDateTime acceptedAt,
            String publicToken,
            String publicPath,
            String publicUrl,
            String whatsappUrl,
            String termPdfSha256,
            String evidenceHash,
            String proofHash,
            boolean userVerified,
            String whatsappSentTo,
            OffsetDateTime sentAt,
            String status,
            String statusLabel,
            String acceptanceIp,
            Double acceptanceLatitude,
            Double acceptanceLongitude,
            Double acceptanceAccuracyMeters,
            OffsetDateTime acceptanceLocationCapturedAt,
            String acceptanceDeviceId,
            String acceptanceDeviceSummary,
            String acceptanceSource,
            String originalDossierSha256
    ) {}
}
