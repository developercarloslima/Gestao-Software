package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "inspection_assets")
public class InspectionAsset {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false)
    private InspectionRequest inspectionRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 20)
    private InspectionAssetType assetType;

    @Column(nullable = false, length = 140)
    private String label;

    @Column(name = "file_name", nullable = false, length = 220)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "drive_file_id", length = 160)
    private String driveFileId;

    @Column(name = "drive_file_url", length = 500)
    private String driveFileUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_kind", nullable = false, length = 20)
    private InspectionAssetStorageKind storageKind;

    @Column(name = "stored_at")
    private OffsetDateTime storedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    protected InspectionAsset() {}

    public static InspectionAsset create(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder,
            String driveFileId,
            String driveFileUrl
    ) {
        InspectionAsset asset = base(request, type, label, fileName, contentType, fileSize, sortOrder);
        asset.storageKind = InspectionAssetStorageKind.DRIVE;
        asset.driveFileId = driveFileId;
        asset.driveFileUrl = driveFileUrl;
        return asset;
    }

    public static InspectionAsset createDatabase(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder,
            OffsetDateTime storedAt,
            OffsetDateTime expiresAt
    ) {
        InspectionAsset asset = base(request, type, label, fileName, contentType, fileSize, sortOrder);
        asset.storageKind = InspectionAssetStorageKind.DATABASE;
        asset.storedAt = storedAt;
        asset.expiresAt = expiresAt;
        return asset;
    }

    private static InspectionAsset base(
            InspectionRequest request,
            InspectionAssetType type,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder
    ) {
        InspectionAsset asset = new InspectionAsset();
        asset.id = UUID.randomUUID();
        asset.inspectionRequest = request;
        asset.assetType = type;
        asset.label = label;
        asset.fileName = fileName;
        asset.contentType = contentType;
        asset.fileSize = fileSize;
        asset.sortOrder = sortOrder;
        return asset;
    }

    public boolean isAvailable() {
        // V83.35: arquivo confirmado não expira mais por idade. O campo expiresAt
        // permanece apenas por compatibilidade com registros históricos.
        return storageKind == InspectionAssetStorageKind.DATABASE && purgedAt == null;
    }

    public void markPurged(OffsetDateTime at) {
        this.purgedAt = at;
    }

    public void replaceStoredFileMetadata(String fileName, String contentType, long fileSize) {
        if (fileName != null && !fileName.isBlank()) this.fileName = fileName;
        if (contentType != null && !contentType.isBlank()) this.contentType = contentType;
        if (fileSize > 0) this.fileSize = fileSize;
    }

    public UUID getId() { return id; }
    public InspectionRequest getInspectionRequest() { return inspectionRequest; }
    public InspectionAssetType getAssetType() { return assetType; }
    public String getLabel() { return label; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public int getSortOrder() { return sortOrder; }
    public String getDriveFileId() { return driveFileId; }
    public String getDriveFileUrl() { return driveFileUrl; }
    public InspectionAssetStorageKind getStorageKind() { return storageKind; }
    public OffsetDateTime getStoredAt() { return storedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getPurgedAt() { return purgedAt; }
}
