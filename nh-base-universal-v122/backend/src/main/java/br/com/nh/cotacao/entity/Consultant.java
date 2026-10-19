package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.text.Normalizer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "consultants")
public class Consultant {
    @Id
    private UUID id;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(name = "normalized_name", nullable = false, unique = true, length = 160)
    private String normalizedName;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false, length = 30)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(name = "collaborator_role", nullable = false, length = 20)
    private CollaboratorRole role;

    @Column(length = 30)
    private String whatsapp;

    @Column(length = 120)
    private String city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_analyst_id")
    private Consultant assignedAnalyst;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_portal_login_at")
    private OffsetDateTime lastPortalLoginAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "commission_mode", nullable = false, length = 20)
    private ConsultantCommissionMode commissionMode = ConsultantCommissionMode.PERCENTAGE;

    @Column(name = "commission_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal commissionValue = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_pix_key_type", length = 20)
    private PixKeyType payoutPixKeyType;

    @Column(name = "payout_pix_key", length = 255)
    private String payoutPixKey;

    protected Consultant() {}

    public static Consultant create(String name, String source) {
        return create(name, source, CollaboratorRole.CONSULTANT);
    }

    public static Consultant create(String name, String source, CollaboratorRole role) {
        String clean = cleanName(name);
        Consultant consultant = new Consultant();
        consultant.id = UUID.randomUUID();
        consultant.name = clean;
        consultant.normalizedName = normalize(clean);
        consultant.active = true;
        consultant.source = source;
        consultant.role = role == null ? CollaboratorRole.CONSULTANT : role;
        consultant.createdAt = OffsetDateTime.now();
        consultant.updatedAt = consultant.createdAt;
        return consultant;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = OffsetDateTime.now();
    }

    public void rename(String name) {
        String clean = cleanName(name);
        this.name = clean;
        this.normalizedName = normalize(clean);
        this.updatedAt = OffsetDateTime.now();
    }

    public void setRole(CollaboratorRole role) {
        if (role == null) throw new IllegalArgumentException("Informe a função do colaborador.");
        this.role = role;
        if (role != CollaboratorRole.CONSULTANT) this.assignedAnalyst = null;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setCity(String city) {
        if (city == null || city.isBlank()) {
            this.city = null;
        } else {
            String clean = city.trim().replaceAll("\\s+", " ");
            if (clean.length() > 120) throw new IllegalArgumentException("A cidade deve possuir no máximo 120 caracteres.");
            this.city = clean;
        }
        this.updatedAt = OffsetDateTime.now();
    }

    public void assignAnalyst(Consultant analyst) {
        if (this.role != CollaboratorRole.CONSULTANT && analyst != null) {
            throw new IllegalArgumentException("Somente consultores podem ser vinculados a um analista.");
        }
        if (analyst != null) {
            if (analyst == this || analyst.getId().equals(this.id)) {
                throw new IllegalArgumentException("O colaborador não pode ser vinculado a si mesmo.");
            }
            if (!analyst.isActive() || analyst.getRole() != CollaboratorRole.ANALYST) {
                throw new IllegalArgumentException("Selecione um analista ativo.");
            }
        }
        this.assignedAnalyst = analyst;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setWhatsapp(String whatsapp) {
        String digits = whatsapp == null ? "" : whatsapp.replaceAll("\\D", "");
        if (!digits.isBlank()) {
            if (digits.length() == 10 || digits.length() == 11) digits = "55" + digits;
            if (digits.length() < 12 || digits.length() > 13 || !digits.startsWith("55")) {
                throw new IllegalArgumentException("Informe um WhatsApp válido do colaborador, com DDD.");
            }
        }
        this.whatsapp = digits.isBlank() ? null : digits;
        this.updatedAt = OffsetDateTime.now();
    }

    public void registerPortalLogin() {
        OffsetDateTime now = OffsetDateTime.now();
        this.lastPortalLoginAt = now;
        this.updatedAt = now;
    }

    public void configureCommission(ConsultantCommissionMode mode, BigDecimal value) {
        if (mode == null) throw new IllegalArgumentException("Informe o tipo de comissão.");
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0) throw new IllegalArgumentException("A comissão não pode ser negativa.");
        if (mode == ConsultantCommissionMode.PERCENTAGE && normalized.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("A comissão percentual não pode ultrapassar 100%.");
        }
        this.commissionMode = mode;
        this.commissionValue = normalized.setScale(2, java.math.RoundingMode.HALF_UP);
        this.updatedAt = OffsetDateTime.now();
    }

    public void configurePayoutPix(PixKeyType type, String key) {
        if (type == null || key == null || key.isBlank()) {
            this.payoutPixKeyType = null;
            this.payoutPixKey = null;
            this.updatedAt = OffsetDateTime.now();
            return;
        }
        String normalized = key.trim();
        if (type == PixKeyType.CPF || type == PixKeyType.CNPJ || type == PixKeyType.PHONE) {
            normalized = normalized.replaceAll("\\D", "");
        } else if (type == PixKeyType.EMAIL) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        }
        switch (type) {
            case CPF -> {
                if (normalized.length() != 11) throw new IllegalArgumentException("Informe uma chave Pix CPF com 11 dígitos.");
            }
            case CNPJ -> {
                if (normalized.length() != 14) throw new IllegalArgumentException("Informe uma chave Pix CNPJ com 14 dígitos.");
            }
            case PHONE -> {
                if (normalized.length() == 13 && normalized.startsWith("55")) normalized = normalized.substring(2);
                if (normalized.length() != 11) throw new IllegalArgumentException("Informe uma chave Pix telefone com DDD e 11 dígitos.");
            }
            case EMAIL -> {
                if (!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Informe uma chave Pix e-mail válida.");
            }
            case EVP -> {
                if (normalized.isBlank()) throw new IllegalArgumentException("Informe uma chave Pix aleatória válida.");
            }
        }
        this.payoutPixKeyType = type;
        this.payoutPixKey = normalized;
        this.updatedAt = OffsetDateTime.now();
    }

    public static String normalize(String value) {
        String withoutAccents = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String cleanName(String name) {
        if (name == null) throw new IllegalArgumentException("Informe o nome do colaborador.");
        String clean = name.trim().replaceAll("\\s+", " ");
        if (clean.length() < 3 || clean.length() > 140) {
            throw new IllegalArgumentException("O nome do colaborador deve ter entre 3 e 140 caracteres.");
        }
        return clean.toUpperCase(Locale.forLanguageTag("pt-BR"));
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public boolean isActive() { return active; }
    public String getSource() { return source; }
    public CollaboratorRole getRole() { return role; }
    public String getWhatsapp() { return whatsapp; }
    public String getCity() { return city; }
    public Consultant getAssignedAnalyst() { return assignedAnalyst; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getLastPortalLoginAt() { return lastPortalLoginAt; }
    public ConsultantCommissionMode getCommissionMode() { return commissionMode; }
    public BigDecimal getCommissionValue() { return commissionValue == null ? BigDecimal.ZERO : commissionValue; }
    public PixKeyType getPayoutPixKeyType() { return payoutPixKeyType; }
    public String getPayoutPixKey() { return payoutPixKey; }
}
