-- Arquivos do Retrato NH passam a ser armazenados no PostgreSQL por 40 dias.
ALTER TABLE inspection_assets
    ALTER COLUMN drive_file_id DROP NOT NULL;

ALTER TABLE inspection_assets
    ADD COLUMN IF NOT EXISTS storage_kind VARCHAR(20) NOT NULL DEFAULT 'DRIVE',
    ADD COLUMN IF NOT EXISTS stored_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS inspection_asset_contents (
    asset_id UUID PRIMARY KEY REFERENCES inspection_assets(id) ON DELETE CASCADE,
    file_data BYTEA NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inspection_assets_expiration
    ON inspection_assets(expires_at)
    WHERE storage_kind = 'DATABASE' AND purged_at IS NULL;

-- Registros antigos permanecem identificados como DRIVE apenas para auditoria.
-- Novos envios utilizam DATABASE e não dependem mais do Google Drive.
COMMENT ON TABLE inspection_asset_contents IS
    'Conteúdo binário de fotos, vídeos, documentos, assinatura e relatório do Retrato NH.';
COMMENT ON COLUMN inspection_assets.expires_at IS
    'Data em que o conteúdo binário será eliminado automaticamente (40 dias após o envio).';
