package br.com.nh.cotacao.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class EventAcceptanceDtos {
    private EventAcceptanceDtos() {}

    public record EventAcceptanceInfo(
            UUID eventId,
            String protocol,
            String associateName,
            String vehiclePlate,
            String vehicleModel,
            boolean eligible,
            boolean accepted,
            OffsetDateTime acceptedAt,
            String publicToken,
            String publicPath,
            String dossierSha256,
            String evidenceHash,
            String proofHash,
            boolean userVerified
    ) {}
}
