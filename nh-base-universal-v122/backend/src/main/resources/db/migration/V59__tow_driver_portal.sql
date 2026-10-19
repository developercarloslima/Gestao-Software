-- Área exclusiva de Guincho/Reboque e rastreabilidade do atendimento.

ALTER TABLE portal_users DROP CONSTRAINT IF EXISTS chk_portal_user_role;
ALTER TABLE portal_users
    ADD CONSTRAINT chk_portal_user_role
    CHECK (role IN ('CONSULTANT', 'ANALYST', 'SUPERVISION_ANALYSIS', 'TOW_DRIVER', 'ADMIN'));

ALTER TABLE nh_event_records
    ADD COLUMN IF NOT EXISTS tow_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS tow_started_by_username VARCHAR(160),
    ADD COLUMN IF NOT EXISTS tow_started_by_name VARCHAR(180),
    ADD COLUMN IF NOT EXISTS tow_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS tow_completed_by_username VARCHAR(160),
    ADD COLUMN IF NOT EXISTS tow_completed_by_name VARCHAR(180);

CREATE INDEX IF NOT EXISTS idx_nh_event_tow_queue
    ON nh_event_records(tow_service, tow_completed_at, created_at DESC);

COMMENT ON COLUMN nh_event_records.tow_started_at IS
    'Primeiro momento em que o atendimento de guincho/reboque foi preenchido ou recebeu foto.';
COMMENT ON COLUMN nh_event_records.tow_completed_at IS
    'Momento em que o guincheiro concluiu o checklist e o registro fotográfico.';
COMMENT ON COLUMN nh_event_records.tow_completed_by_username IS
    'Usuário de acesso que concluiu o atendimento de guincho/reboque.';
