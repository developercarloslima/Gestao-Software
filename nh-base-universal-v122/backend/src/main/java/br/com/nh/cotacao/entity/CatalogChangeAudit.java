package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "catalog_change_audit")
public class CatalogChangeAudit {
    public static final int ITEM_TYPE_MAX_LENGTH = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_type", nullable = false, length = ITEM_TYPE_MAX_LENGTH)
    private String itemType;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "item_key", length = 120)
    private String itemKey;

    @Column(nullable = false, length = 260)
    private String description;

    @Column(name = "old_value", precision = 14, scale = 2)
    private BigDecimal oldValue;

    @Column(name = "new_value", precision = 14, scale = 2)
    private BigDecimal newValue;

    @Column(name = "old_text", length = 2000)
    private String oldText;

    @Column(name = "new_text", length = 2000)
    private String newText;

    @Column(name = "changed_by", nullable = false, length = 160)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    protected CatalogChangeAudit() {}

    public static CatalogChangeAudit create(
            String type,
            Long id,
            String description,
            BigDecimal oldValue,
            BigDecimal newValue,
            String changedBy
    ) {
        CatalogChangeAudit audit = base(type, id, id == null ? null : String.valueOf(id), description, changedBy);
        audit.oldValue = oldValue;
        audit.newValue = newValue;
        return audit;
    }

    public static CatalogChangeAudit createText(
            String type,
            Long id,
            String description,
            String oldText,
            String newText,
            String changedBy
    ) {
        return createText(type, id, id == null ? null : String.valueOf(id), description, oldText, newText, changedBy);
    }

    public static CatalogChangeAudit createText(
            String type,
            Long id,
            String itemKey,
            String description,
            String oldText,
            String newText,
            String changedBy
    ) {
        CatalogChangeAudit audit = base(type, id, itemKey, description, changedBy);
        audit.oldText = truncate(oldText);
        audit.newText = truncate(newText);
        return audit;
    }

    private static CatalogChangeAudit base(
            String type,
            Long id,
            String itemKey,
            String description,
            String changedBy
    ) {
        String normalizedType = type == null ? "" : type.trim();
        if (normalizedType.isEmpty()) {
            throw new IllegalArgumentException("Identificador interno de auditoria não informado.");
        }
        if (normalizedType.length() > ITEM_TYPE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Identificador interno de auditoria excede o limite de " + ITEM_TYPE_MAX_LENGTH + " caracteres: " + normalizedType
            );
        }
        CatalogChangeAudit audit = new CatalogChangeAudit();
        audit.itemType = normalizedType;
        audit.itemId = id;
        audit.itemKey = truncateKey(itemKey);
        audit.description = description == null ? "Alteração administrativa" : description.substring(0, Math.min(260, description.length()));
        audit.changedBy = changedBy;
        audit.changedAt = OffsetDateTime.now();
        return audit;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private static String truncateKey(String value) {
        if (value == null) return null;
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    public Long getId() { return id; }
    public String getItemType() { return itemType; }
    public Long getItemId() { return itemId; }
    public String getItemKey() { return itemKey; }
    public String getDescription() { return description; }
    public BigDecimal getOldValue() { return oldValue; }
    public BigDecimal getNewValue() { return newValue; }
    public String getOldText() { return oldText; }
    public String getNewText() { return newText; }
    public String getChangedBy() { return changedBy; }
    public OffsetDateTime getChangedAt() { return changedAt; }
}
