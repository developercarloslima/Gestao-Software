CREATE TABLE inspection_requests (
    id UUID PRIMARY KEY,
    public_token VARCHAR(80) NOT NULL UNIQUE,
    request_type VARCHAR(30) NOT NULL,
    associate_name VARCHAR(140) NOT NULL,
    cpf VARCHAR(14) NOT NULL,
    whatsapp VARCHAR(30),
    plate VARCHAR(10) NOT NULL,
    consultant_id UUID NOT NULL REFERENCES consultants(id),
    consultant_name VARCHAR(140) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    drive_folder_id VARCHAR(160),
    drive_folder_url VARCHAR(500),
    report_file_id VARCHAR(160),
    report_url VARCHAR(500)
);

CREATE TABLE inspection_assets (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES inspection_requests(id) ON DELETE CASCADE,
    asset_type VARCHAR(20) NOT NULL,
    label VARCHAR(140) NOT NULL,
    file_name VARCHAR(220) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sort_order INTEGER NOT NULL,
    drive_file_id VARCHAR(160) NOT NULL,
    drive_file_url VARCHAR(500)
);

CREATE INDEX idx_inspection_consultant ON inspection_requests(consultant_id);
CREATE INDEX idx_inspection_created_at ON inspection_requests(created_at DESC);
CREATE INDEX idx_inspection_plate ON inspection_requests(plate);
CREATE INDEX idx_inspection_status ON inspection_requests(status);
CREATE INDEX idx_inspection_assets_request ON inspection_assets(inspection_id, sort_order);
