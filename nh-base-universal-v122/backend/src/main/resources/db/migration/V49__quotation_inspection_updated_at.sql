-- Datas reais de última modificação para filtros e ordenação do painel administrativo.

ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE quotations
SET updated_at = GREATEST(
    created_at,
    decided_at,
    reviewed_at,
    inspection_completed_at
)
WHERE updated_at IS NULL;

ALTER TABLE quotations
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_quotations_updated_at
    ON quotations(updated_at DESC);

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;

UPDATE inspection_requests
SET updated_at = GREATEST(
    created_at,
    completed_at,
    reviewed_at,
    registration_completed_at,
    supervision_note_updated_at,
    decision_message_sent_at,
    accepted_at
)
WHERE updated_at IS NULL;

ALTER TABLE inspection_requests
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_inspection_requests_updated_at
    ON inspection_requests(updated_at DESC);
