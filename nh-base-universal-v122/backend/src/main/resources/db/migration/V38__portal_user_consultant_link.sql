ALTER TABLE portal_users
    ADD COLUMN IF NOT EXISTS consultant_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_portal_users_consultant'
    ) THEN
        ALTER TABLE portal_users
            ADD CONSTRAINT fk_portal_users_consultant
            FOREIGN KEY (consultant_id)
            REFERENCES consultants(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_portal_users_consultant_id
    ON portal_users(consultant_id)
    WHERE consultant_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_portal_users_consultant_id
    ON portal_users(consultant_id);

COMMENT ON COLUMN portal_users.consultant_id IS
    'Consultor específico vinculado à conta. NULL mantém o comportamento legado dos usuários padrão.';
