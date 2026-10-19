-- Administração completa da operação NH.

-- Mantém o nome do consultor gravado nas atividades e permite excluir o cadastro.
ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS quotations_consultant_id_fkey;
ALTER TABLE quotations
    ADD CONSTRAINT quotations_consultant_id_fkey
        FOREIGN KEY (consultant_id) REFERENCES consultants(id) ON DELETE SET NULL;

ALTER TABLE inspection_requests
    ALTER COLUMN consultant_id DROP NOT NULL;
ALTER TABLE inspection_requests
    DROP CONSTRAINT IF EXISTS inspection_requests_consultant_id_fkey;
ALTER TABLE inspection_requests
    ADD CONSTRAINT inspection_requests_consultant_id_fkey
        FOREIGN KEY (consultant_id) REFERENCES consultants(id) ON DELETE SET NULL;

-- Análise administrativa de cotações e solicitações do Retrato NH.
ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS admin_note VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS admin_note VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;

-- Configurações de comunicação alteráveis sem reiniciar a aplicação.
CREATE TABLE IF NOT EXISTS app_settings (
    setting_key VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(500),
    updated_by VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO app_settings(setting_key, setting_value, updated_by, updated_at)
VALUES
    ('COTATION_TEAM_EMAIL', '', 'SYSTEM', NOW()),
    ('COTATION_TEAM_WHATSAPP', '', 'SYSTEM', NOW())
ON CONFLICT (setting_key) DO NOTHING;

-- Amplia a auditoria para alterações de UUIDs e textos mais detalhados.
ALTER TABLE catalog_change_audit
    ALTER COLUMN item_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS item_key VARCHAR(120),
    ALTER COLUMN old_text TYPE VARCHAR(2000),
    ALTER COLUMN new_text TYPE VARCHAR(2000);

CREATE INDEX IF NOT EXISTS idx_catalog_audit_type_time
    ON catalog_change_audit(item_type, changed_at DESC);
