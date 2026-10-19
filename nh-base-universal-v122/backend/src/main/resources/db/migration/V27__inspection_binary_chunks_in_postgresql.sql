-- Persistência definitiva dos arquivos do Retrato NH no PostgreSQL.
-- Cada parte recebida é gravada no banco imediatamente; o servidor não mantém
-- mais um volume permanente de uploads incompletos.

CREATE TABLE IF NOT EXISTS inspection_asset_blobs (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES inspection_requests(id) ON DELETE CASCADE,
    asset_id UUID UNIQUE REFERENCES inspection_assets(id) ON DELETE CASCADE,
    asset_type VARCHAR(20) NOT NULL,
    sort_order INTEGER NOT NULL,
    upload_id VARCHAR(140) NOT NULL,
    label VARCHAR(140) NOT NULL,
    file_name VARCHAR(220) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    total_size BIGINT NOT NULL CHECK (total_size > 0),
    total_chunks INTEGER NOT NULL CHECK (total_chunks BETWEEN 1 AND 512),
    status VARCHAR(20) NOT NULL CHECK (status IN ('UPLOADING', 'COMPLETE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_inspection_asset_blob_slot UNIQUE (inspection_id, asset_type, sort_order),
    CONSTRAINT chk_inspection_asset_blob_completion CHECK (
        (status = 'UPLOADING' AND asset_id IS NULL AND completed_at IS NULL)
        OR
        (status = 'COMPLETE' AND asset_id IS NOT NULL AND completed_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS inspection_asset_blob_chunks (
    blob_id UUID NOT NULL REFERENCES inspection_asset_blobs(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    chunk_size INTEGER NOT NULL CHECK (chunk_size > 0),
    chunk_sha256 VARCHAR(64) NOT NULL,
    chunk_data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blob_id, chunk_index),
    CONSTRAINT chk_inspection_asset_blob_chunk_size
        CHECK (octet_length(chunk_data) = chunk_size)
);

CREATE INDEX IF NOT EXISTS idx_inspection_asset_blobs_asset
    ON inspection_asset_blobs(asset_id)
    WHERE asset_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_inspection_asset_blobs_cleanup
    ON inspection_asset_blobs(updated_at)
    WHERE status = 'UPLOADING';

COMMENT ON TABLE inspection_asset_blobs IS
    'Sessão e metadados do conteúdo binário do Retrato NH armazenado diretamente no PostgreSQL.';
COMMENT ON TABLE inspection_asset_blob_chunks IS
    'Partes binárias de fotos, vídeos, documentos, assinatura e relatórios. Cada parte é persistida imediatamente.';
