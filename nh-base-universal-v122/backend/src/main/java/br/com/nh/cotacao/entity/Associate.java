package br.com.nh.cotacao.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "associates")
public class Associate {
    @Id private UUID id;
    @Column(nullable = false, length = 160) private String name;
    @Column(name = "normalized_name", nullable = false, length = 180) private String normalizedName;
    @Column(length = 14) private String document;
    @Column(length = 30) private String whatsapp;
    @Column(length = 180) private String email;
    @Column(name = "birth_date") private LocalDate birthDate;
    @Column(name = "secondary_phone", length = 30) private String secondaryPhone;
    @Column(name = "postal_code", length = 12) private String postalCode;
    @Column(name = "address_line", length = 220) private String addressLine;
    @Column(name = "address_number", length = 30) private String addressNumber;
    @Column(name = "address_complement", length = 120) private String addressComplement;
    @Column(name = "neighborhood", length = 120) private String neighborhood;
    @Column(length = 120) private String city;
    @Column(length = 2) private String state;
    @Column(name = "asaas_customer_id", length = 80) private String asaasCustomerId;
    @Column(nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    protected Associate() {}

    public static Associate create(String name, String document, String whatsapp, String email) {
        Associate a = new Associate();
        a.id = UUID.randomUUID();
        a.name = cleanName(name);
        a.normalizedName = Consultant.normalize(a.name);
        a.document = normalizeDocument(document);
        a.whatsapp = normalizePhone(whatsapp);
        a.email = normalizeEmail(email);
        a.active = true;
        a.createdAt = OffsetDateTime.now();
        a.updatedAt = a.createdAt;
        return a;
    }

    public void update(String name, String document, String whatsapp, String email) {
        this.name = cleanName(name);
        this.normalizedName = Consultant.normalize(this.name);
        this.document = normalizeDocument(document);
        this.whatsapp = normalizePhone(whatsapp);
        this.email = normalizeEmail(email);
        this.updatedAt = OffsetDateTime.now();
    }


    public void updateProfile(String name, String whatsapp, String email, LocalDate birthDate, String secondaryPhone,
                              String postalCode, String addressLine, String addressNumber, String addressComplement,
                              String neighborhood, String city, String state) {
        this.name = cleanName(name);
        this.normalizedName = Consultant.normalize(this.name);
        this.whatsapp = normalizePhone(whatsapp);
        this.email = normalizeEmail(email);
        this.birthDate = birthDate;
        this.secondaryPhone = normalizePhone(secondaryPhone);
        this.postalCode = blankToNull(postalCode == null ? null : postalCode.replaceAll("\\D", ""));
        this.addressLine = cleanOptional(addressLine, 220);
        this.addressNumber = cleanOptional(addressNumber, 30);
        this.addressComplement = cleanOptional(addressComplement, 120);
        this.neighborhood = cleanOptional(neighborhood, 120);
        this.city = cleanOptional(city, 120);
        String uf = blankToNull(state == null ? null : state.trim().toUpperCase(java.util.Locale.ROOT));
        if (uf != null && uf.length() != 2) throw new IllegalArgumentException("UF deve possuir 2 caracteres.");
        this.state = uf;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setAsaasCustomerId(String id) { this.asaasCustomerId = blankToNull(id); this.updatedAt = OffsetDateTime.now(); }
    public void setActive(boolean active) { this.active = active; this.updatedAt = OffsetDateTime.now(); }

    private static String cleanName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Informe o nome do associado.");
        String clean = value.trim().replaceAll("\\s+", " ");
        if (clean.length() > 160) throw new IllegalArgumentException("Nome do associado muito longo.");
        return clean;
    }
    public static String normalizeDocument(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.isBlank()) return null;
        if (digits.length() != 11 && digits.length() != 14) throw new IllegalArgumentException("CPF/CNPJ inválido.");
        return digits;
    }
    private static String normalizePhone(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }
    private static String normalizeEmail(String value) {
        return blankToNull(value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT));
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String cleanOptional(String value, int max) {
        String clean = blankToNull(value);
        if (clean == null) return null;
        clean = clean.replaceAll("\\s+", " ");
        return clean.substring(0, Math.min(max, clean.length()));
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getDocument() { return document; }
    public String getWhatsapp() { return whatsapp; }
    public String getEmail() { return email; }
    public LocalDate getBirthDate() { return birthDate; }
    public String getSecondaryPhone() { return secondaryPhone; }
    public String getPostalCode() { return postalCode; }
    public String getAddressLine() { return addressLine; }
    public String getAddressNumber() { return addressNumber; }
    public String getAddressComplement() { return addressComplement; }
    public String getNeighborhood() { return neighborhood; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getAsaasCustomerId() { return asaasCustomerId; }
    public boolean isActive() { return active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
