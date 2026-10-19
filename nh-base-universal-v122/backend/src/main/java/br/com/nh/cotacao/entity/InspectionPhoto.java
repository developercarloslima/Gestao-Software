package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "inspection_photos")
public class InspectionPhoto {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(name = "file_name", nullable = false, length = 180)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "drive_file_id", nullable = false, length = 160)
    private String driveFileId;

    @Column(name = "drive_file_url", length = 500)
    private String driveFileUrl;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected InspectionPhoto() {
    }

    static InspectionPhoto create(
            Quotation quotation,
            String label,
            String fileName,
            String contentType,
            long fileSize,
            int sortOrder,
            String driveFileId,
            String driveFileUrl
    ) {
        InspectionPhoto photo = new InspectionPhoto();
        photo.id = UUID.randomUUID();
        photo.quotation = quotation;
        photo.label = label;
        photo.fileName = fileName;
        photo.contentType = contentType;
        photo.fileSize = fileSize;
        photo.sortOrder = sortOrder;
        photo.driveFileId = driveFileId;
        photo.driveFileUrl = driveFileUrl;
        photo.createdAt = OffsetDateTime.now();
        return photo;
    }

    public UUID getId() { return id; }
    public Quotation getQuotation() { return quotation; }
    public String getLabel() { return label; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public Long getFileSize() { return fileSize; }
    public Integer getSortOrder() { return sortOrder; }
    public String getDriveFileId() { return driveFileId; }
    public String getDriveFileUrl() { return driveFileUrl; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
