package br.com.nh.cotacao.dto;

import br.com.nh.cotacao.security.PortalRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class AdminUserDtos {
    private AdminUserDtos() {}

    public record PortalUserResponse(
            UUID id,
            String username,
            String displayName,
            PortalRole role,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime passwordChangedAt,
            OffsetDateTime lastLoginAt,
            String createdBy,
            UUID consultantId,
            String consultantName,
            boolean mustChangePassword,
            boolean faceValidationRequired,
            boolean faceEnrolled,
            OffsetDateTime faceEnrolledAt,
            boolean passkeyRequired,
            boolean passkeyEnrolled,
            OffsetDateTime passkeyEnrolledAt
    ) {}

    public record CreatePortalUserRequest(
            @NotBlank @Size(min = 3, max = 160) String username,
            @Size(max = 160) String displayName,
            // Campo legado opcional; cadastro manual sempre usa o padrão do tenant.
            @Size(max = 120) String password,
            @NotNull PortalRole role,
            UUID consultantId,
            @Size(max = 140) String newConsultantName
    ) {}

    public record UpdatePortalUserRequest(
            @Size(min = 3, max = 160) String username,
            @Size(max = 160) String displayName,
            PortalRole role,
            Boolean active,
            UUID consultantId,
            @Size(max = 140) String newConsultantName
    ) {}

    public record ChangePortalUserPasswordRequest(
            @NotBlank @Size(min = 8, max = 120) String password
    ) {}
}
