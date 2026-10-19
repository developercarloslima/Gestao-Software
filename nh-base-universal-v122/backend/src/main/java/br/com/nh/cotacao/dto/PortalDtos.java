package br.com.nh.cotacao.dto;

import br.com.nh.cotacao.entity.CollaboratorRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class PortalDtos {
    private PortalDtos() {}

    public record ConsultantResponse(
            UUID id,
            String name,
            boolean active,
            CollaboratorRole role,
            String whatsapp,
            String city,
            UUID assignedAnalystId,
            String assignedAnalystName,
            long assignedConsultantCount,
            String source,
            OffsetDateTime createdAt,
            OffsetDateTime lastPortalLoginAt,
            long quoteCount,
            long inspectionCount
    ) {}

    public record CreateConsultantRequest(
            @NotBlank @Size(min = 3, max = 140) String name,
            CollaboratorRole role,
            @Size(max = 30) String whatsapp,
            @Size(max = 120) String city,
            UUID assignedAnalystId
    ) {}

    public record UpdateConsultantRequest(
            @Size(min = 3, max = 140) String name,
            Boolean active,
            CollaboratorRole role,
            @Size(max = 30) String whatsapp,
            @Size(max = 120) String city,
            UUID assignedAnalystId
    ) {}

    public record UpdateConsultantWhatsappRequest(
            @NotBlank @Size(max = 30) String whatsapp
    ) {}
}
