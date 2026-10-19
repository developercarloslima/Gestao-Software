ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS collaborator_role VARCHAR(20);

UPDATE consultants
SET collaborator_role = 'CONSULTANT'
WHERE collaborator_role IS NULL OR btrim(collaborator_role) = '';

ALTER TABLE consultants
    ALTER COLUMN collaborator_role SET DEFAULT 'CONSULTANT',
    ALTER COLUMN collaborator_role SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_consultants_collaborator_role'
    ) THEN
        ALTER TABLE consultants
            ADD CONSTRAINT ck_consultants_collaborator_role
            CHECK (collaborator_role IN ('CONSULTANT', 'ANALYST'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_consultants_active_role_name
    ON consultants(active, collaborator_role, name);

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS reviewed_by_collaborator_id UUID,
    ADD COLUMN IF NOT EXISTS reviewed_by_name VARCHAR(160),
    ADD COLUMN IF NOT EXISTS reviewed_by_role VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_inspection_reviewed_by_collaborator'
    ) THEN
        ALTER TABLE inspection_requests
            ADD CONSTRAINT fk_inspection_reviewed_by_collaborator
            FOREIGN KEY (reviewed_by_collaborator_id)
            REFERENCES consultants(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_inspection_reviewed_by_collaborator
    ON inspection_requests(reviewed_by_collaborator_id);

COMMENT ON COLUMN consultants.collaborator_role IS
    'Cargo operacional do colaborador: CONSULTANT ou ANALYST.';

COMMENT ON COLUMN portal_users.consultant_id IS
    'Colaborador específico vinculado à conta. Para contas CONSULTANT aponta para consultor; para ANALYST pode apontar para analista. NULL preserva usuários padrão.';

COMMENT ON COLUMN inspection_requests.reviewed_by_name IS
    'Nome gravado no momento da análise para preservar o responsável histórico.';
