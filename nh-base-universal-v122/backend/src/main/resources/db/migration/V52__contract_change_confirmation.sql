-- V52: fluxo definitivo de confirmação de alteração de FIPE/mensalidade.
-- Mantemos a V51 anterior intacta por compatibilidade com ambientes que possam tê-la aplicado.
ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS pending_contract_fipe_value NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS pending_contract_monthly_value NUMERIC(14,2),
    ADD COLUMN IF NOT EXISTS contract_change_requested_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS contract_change_requested_by VARCHAR(160),
    ADD COLUMN IF NOT EXISTS contract_change_decided_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS contract_change_accepted BOOLEAN,
    ADD COLUMN IF NOT EXISTS contract_change_keep_optionals BOOLEAN;

CREATE INDEX IF NOT EXISTS idx_inspection_requests_contract_change_pending
    ON inspection_requests (contract_change_requested_at)
    WHERE pending_contract_fipe_value IS NOT NULL
      AND pending_contract_monthly_value IS NOT NULL;
