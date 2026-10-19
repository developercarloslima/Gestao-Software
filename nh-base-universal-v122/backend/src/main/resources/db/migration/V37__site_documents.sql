-- Arquivos institucionais gerenciáveis pelo painel administrativo.
CREATE TABLE IF NOT EXISTS site_documents (
    document_key VARCHAR(80) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    file_data BYTEA NOT NULL,
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE site_documents IS
    'Arquivos institucionais do site que podem ser substituídos pelo painel administrativo.';
