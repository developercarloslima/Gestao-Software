-- Equipes de análise, supervisão e troca obrigatória de senha inicial.

ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS city VARCHAR(120),
    ADD COLUMN IF NOT EXISTS assigned_analyst_id UUID;

ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_collaborator_role;
ALTER TABLE consultants
    ADD CONSTRAINT ck_consultants_collaborator_role
    CHECK (collaborator_role IN ('CONSULTANT', 'ANALYST', 'SUPERVISION_ANALYSIS'));

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_consultant_assigned_analyst') THEN
        ALTER TABLE consultants
            ADD CONSTRAINT fk_consultant_assigned_analyst
            FOREIGN KEY (assigned_analyst_id) REFERENCES consultants(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_consultants_assigned_analyst
    ON consultants(assigned_analyst_id, active);

ALTER TABLE portal_users
    ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;
ALTER TABLE portal_users
    ADD CONSTRAINT chk_portal_user_role
    CHECK (role IN ('CONSULTANT', 'ANALYST', 'SUPERVISION_ANALYSIS', 'ADMIN'));

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS assigned_analyst_id UUID,
    ADD COLUMN IF NOT EXISTS assigned_analyst_name VARCHAR(160),
    ADD COLUMN IF NOT EXISTS analysis_stage VARCHAR(30),
    ADD COLUMN IF NOT EXISTS registration_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS registration_completed_by_name VARCHAR(160);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_inspection_assigned_analyst') THEN
        ALTER TABLE inspection_requests
            ADD CONSTRAINT fk_inspection_assigned_analyst
            FOREIGN KEY (assigned_analyst_id) REFERENCES consultants(id) ON DELETE SET NULL;
    END IF;
END $$;

UPDATE inspection_requests
SET analysis_stage = CASE
    WHEN status IN ('APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED') THEN 'FINISHED'
    ELSE 'ANALYST_QUEUE'
END
WHERE analysis_stage IS NULL OR btrim(analysis_stage) = '';

ALTER TABLE inspection_requests
    ALTER COLUMN analysis_stage SET DEFAULT 'ANALYST_QUEUE',
    ALTER COLUMN analysis_stage SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_inspection_analysis_stage') THEN
        ALTER TABLE inspection_requests
            ADD CONSTRAINT ck_inspection_analysis_stage
            CHECK (analysis_stage IN ('ANALYST_QUEUE', 'ANALYST_PENDING', 'SUPERVISION_QUEUE', 'FINISHED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_inspection_analysis_routing
    ON inspection_requests(analysis_stage, assigned_analyst_id, created_at DESC);

COMMENT ON COLUMN consultants.assigned_analyst_id IS
    'Analista responsável pelo consultor. Um analista pode receber no máximo 15 consultores, validado pela aplicação.';
COMMENT ON COLUMN portal_users.must_change_password IS
    'Quando TRUE, o usuário só pode acessar /api/auth/me e trocar a própria senha antes de usar o sistema.';
COMMENT ON COLUMN inspection_requests.analysis_stage IS
    'Fila operacional: ANALYST_QUEUE, ANALYST_PENDING, SUPERVISION_QUEUE ou FINISHED.';
