ALTER TABLE quotations
    ADD COLUMN drive_folder_id VARCHAR(160),
    ADD COLUMN drive_folder_url VARCHAR(500),
    ADD COLUMN drive_pdf_file_id VARCHAR(160),
    ADD COLUMN drive_pdf_url VARCHAR(500),
    ADD COLUMN inspection_completed_at TIMESTAMPTZ;

CREATE TABLE inspection_photos (
    id UUID PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    label VARCHAR(120) NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    file_size BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,
    drive_file_id VARCHAR(160) NOT NULL,
    drive_file_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_inspection_photo_size CHECK (file_size > 0),
    CONSTRAINT uk_inspection_photo_order UNIQUE (quotation_id, sort_order)
);

CREATE INDEX idx_inspection_photos_quotation
    ON inspection_photos(quotation_id, sort_order);
