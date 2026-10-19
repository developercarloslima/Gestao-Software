ALTER TABLE financial_charges ADD COLUMN IF NOT EXISTS provider_fee NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE financial_charges ADD COLUMN IF NOT EXISTS gross_value NUMERIC(14,2);
UPDATE financial_charges SET gross_value = COALESCE(gross_value, value + provider_fee);
ALTER TABLE financial_charges ALTER COLUMN gross_value SET NOT NULL;
DO $$ BEGIN
  ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charge_provider_fee CHECK (provider_fee >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charge_gross_value CHECK (gross_value >= value);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

ALTER TABLE consultant_withdrawals ADD COLUMN IF NOT EXISTS transaction_fee NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE consultant_withdrawals ADD COLUMN IF NOT EXISTS net_amount NUMERIC(14,2);
UPDATE consultant_withdrawals SET net_amount = COALESCE(net_amount, GREATEST(amount - transaction_fee, 0));
ALTER TABLE consultant_withdrawals ALTER COLUMN net_amount SET NOT NULL;
DO $$ BEGIN
  ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_fee CHECK (transaction_fee >= 0);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_net CHECK (net_amount >= 0 AND net_amount <= amount);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
