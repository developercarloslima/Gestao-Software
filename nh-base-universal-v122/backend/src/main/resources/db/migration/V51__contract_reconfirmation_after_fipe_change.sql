-- V14: confirmação do cliente quando a correção da FIPE altera o valor do plano.
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_created_at TIMESTAMPTZ;
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_accepted_at TIMESTAMPTZ;
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_notified_at TIMESTAMPTZ;
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_keep_optionals BOOLEAN;
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_previous_fipe NUMERIC(14,2);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_previous_monthly NUMERIC(14,2);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_fipe NUMERIC(14,2);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_base_monthly NUMERIC(14,2);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_mandatory_monthly NUMERIC(14,2);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_one_time_fee NUMERIC(14,2);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_proposed_mandatory_description VARCHAR(240);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_monthly_with_optionals NUMERIC(14,2);
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS contract_reconfirmation_monthly_without_optionals NUMERIC(14,2);

CREATE INDEX IF NOT EXISTS idx_inspection_contract_reconfirmation_pending
    ON inspection_requests (contract_reconfirmation_required, analysis_stage)
    WHERE contract_reconfirmation_required = TRUE;

COMMENT ON COLUMN inspection_requests.contract_reconfirmation_required IS
    'TRUE quando a correção da FIPE alterou a mensalidade e o cliente precisa reconfirmar o novo valor antes de Cadastro feito.';
