-- Novos contratos: agenda anual de cobranças emitidas antecipadamente no Asaas.
-- Empresas preexistentes permanecem no fluxo legado até migração explícita.
ALTER TABLE platform_companies
  ADD COLUMN IF NOT EXISTS advance_billing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS next_renewal_date DATE,
  ADD COLUMN IF NOT EXISTS renewal_choice VARCHAR(20) NOT NULL DEFAULT 'AUTO';

ALTER TABLE platform_companies DROP CONSTRAINT IF EXISTS ck_platform_company_contract_term;
ALTER TABLE platform_companies ADD CONSTRAINT ck_platform_company_contract_term
  CHECK (contract_term IN ('MONTHLY','QUARTERLY','SEMIANNUAL','ANNUAL'));

ALTER TABLE platform_companies ADD CONSTRAINT ck_platform_company_renewal_choice
  CHECK (renewal_choice IN ('AUTO','AUTO_ACK','NON_RENEW'));

CREATE INDEX IF NOT EXISTS idx_platform_advance_billing_due
  ON platform_companies(next_renewal_date)
  WHERE advance_billing_enabled = TRUE AND renewal_choice = 'AUTO';
