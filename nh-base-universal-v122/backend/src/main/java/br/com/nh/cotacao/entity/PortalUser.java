package br.com.nh.cotacao.entity;

import br.com.nh.cotacao.security.PortalRole;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "portal_users")
public class PortalUser {
    @Id
    private UUID id;

    @Column(nullable = false, length = 160)
    private String username;

    @Column(name = "normalized_username", nullable = false, unique = true, length = 160)
    private String normalizedUsername;

    @Column(name = "display_name", length = 160)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 120)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PortalRole role;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "password_changed_at", nullable = false)
    private OffsetDateTime passwordChangedAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_by", length = 160)
    private String createdBy;

    @Column(name = "consultant_id")
    private UUID consultantId;

    @Column(name = "associate_id")
    private UUID associateId;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "face_enrolled_at")
    private OffsetDateTime faceEnrolledAt;

    @Column(name = "passkey_credential_id", length = 1024)
    private String passkeyCredentialId;

    @Column(name = "passkey_public_key", columnDefinition = "bytea")
    private byte[] passkeyPublicKey;

    @Column(name = "passkey_algorithm")
    private Integer passkeyAlgorithm;

    @Column(name = "passkey_sign_count")
    private Long passkeySignCount;

    @Column(name = "passkey_registration_challenge", length = 160)
    private String passkeyRegistrationChallenge;

    @Column(name = "passkey_registration_expires_at")
    private OffsetDateTime passkeyRegistrationExpiresAt;

    @Column(name = "passkey_assertion_challenge", length = 160)
    private String passkeyAssertionChallenge;

    @Column(name = "passkey_assertion_expires_at")
    private OffsetDateTime passkeyAssertionExpiresAt;

    @Column(name = "passkey_origin", length = 320)
    private String passkeyOrigin;

    @Column(name = "passkey_rp_id", length = 253)
    private String passkeyRpId;

    @Column(name = "passkey_enrolled_at")
    private OffsetDateTime passkeyEnrolledAt;

    protected PortalUser() {}

    public static PortalUser create(
            String username,
            String displayName,
            String passwordHash,
            PortalRole role,
            String createdBy
    ) {
        PortalUser user = new PortalUser();
        user.id = UUID.randomUUID();
        user.setUsernameInternal(username);
        user.displayName = cleanOptional(displayName, 160);
        user.passwordHash = requireHash(passwordHash);
        user.role = requireRole(role);
        user.active = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = user.createdAt;
        user.passwordChangedAt = user.createdAt;
        user.createdBy = cleanOptional(createdBy, 160);
        user.mustChangePassword = false;
        return user;
    }

    public void updateProfile(String username, String displayName, PortalRole role) {
        if (username != null && !username.isBlank()) setUsernameInternal(username);
        if (displayName != null) this.displayName = cleanOptional(displayName, 160);
        if (role != null) this.role = requireRole(role);
        this.updatedAt = OffsetDateTime.now();
    }

    public void linkConsultant(UUID consultantId) {
        this.consultantId = consultantId;
        this.updatedAt = OffsetDateTime.now();
    }

    public void linkAssociate(UUID associateId) {
        this.associateId = associateId;
        if (associateId != null) this.consultantId = null;
        this.updatedAt = OffsetDateTime.now();
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = requireHash(passwordHash);
        this.passwordChangedAt = OffsetDateTime.now();
        this.mustChangePassword = false;
        this.updatedAt = this.passwordChangedAt;
    }

    public void requirePasswordChange() {
        this.mustChangePassword = true;
        this.updatedAt = OffsetDateTime.now();
    }

    public void clearPasswordChangeRequirement() {
        if (!this.mustChangePassword) return;
        this.mustChangePassword = false;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markFaceEnrolled() {
        this.faceEnrolledAt = OffsetDateTime.now();
        this.updatedAt = this.faceEnrolledAt;
    }

    public void clearFaceEnrollment() {
        this.faceEnrolledAt = null;
        this.updatedAt = OffsetDateTime.now();
    }

    public void clearPasskeyEnrollment() {
        this.passkeyCredentialId = null;
        this.passkeyPublicKey = null;
        this.passkeyAlgorithm = null;
        this.passkeySignCount = null;
        this.passkeyRegistrationChallenge = null;
        this.passkeyRegistrationExpiresAt = null;
        this.passkeyAssertionChallenge = null;
        this.passkeyAssertionExpiresAt = null;
        this.passkeyOrigin = null;
        this.passkeyRpId = null;
        this.passkeyEnrolledAt = null;
        this.updatedAt = OffsetDateTime.now();
    }

    public void registerLogin() {
        OffsetDateTime now = OffsetDateTime.now();
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    private void setUsernameInternal(String username) {
        String clean = username == null ? "" : username.trim();
        if (clean.length() < 3 || clean.length() > 160) {
            throw new IllegalArgumentException("O usuário deve ter entre 3 e 160 caracteres.");
        }
        if (clean.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("O usuário não pode conter espaços.");
        }
        this.username = clean;
        this.normalizedUsername = normalizeUsername(clean);
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireHash(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Hash de senha inválido.");
        return value;
    }

    private static PortalRole requireRole(PortalRole role) {
        if (role == null) throw new IllegalArgumentException("Informe o perfil de acesso.");
        return role;
    }

    private static String cleanOptional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.substring(0, Math.min(max, clean.length()));
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getNormalizedUsername() { return normalizedUsername; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public PortalRole getRole() { return role; }
    public boolean isActive() { return active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public String getCreatedBy() { return createdBy; }
    public UUID getConsultantId() { return consultantId; }
    public UUID getAssociateId() { return associateId; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public OffsetDateTime getFaceEnrolledAt() { return faceEnrolledAt; }
    public String getPasskeyCredentialId() { return passkeyCredentialId; }
    public byte[] getPasskeyPublicKey() { return passkeyPublicKey == null ? null : passkeyPublicKey.clone(); }
    public Integer getPasskeyAlgorithm() { return passkeyAlgorithm; }
    public long getPasskeySignCount() { return passkeySignCount == null ? 0L : passkeySignCount; }
    public String getPasskeyRegistrationChallenge() { return passkeyRegistrationChallenge; }
    public OffsetDateTime getPasskeyRegistrationExpiresAt() { return passkeyRegistrationExpiresAt; }
    public String getPasskeyAssertionChallenge() { return passkeyAssertionChallenge; }
    public OffsetDateTime getPasskeyAssertionExpiresAt() { return passkeyAssertionExpiresAt; }
    public String getPasskeyOrigin() { return passkeyOrigin; }
    public String getPasskeyRpId() { return passkeyRpId; }
    public OffsetDateTime getPasskeyEnrolledAt() { return passkeyEnrolledAt; }
}
