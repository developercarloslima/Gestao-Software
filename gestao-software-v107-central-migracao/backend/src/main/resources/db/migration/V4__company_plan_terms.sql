ALTER TABLE platform_companies ADD COLUMN IF NOT EXISTS plan_code VARCHAR(40);
ALTER TABLE platform_companies ADD COLUMN IF NOT EXISTS contract_term VARCHAR(20);

UPDATE platform_companies
   SET plan_code = COALESCE(NULLIF(plan_code,''),
       CASE UPPER(plan_name)
         WHEN 'START' THEN 'START'
         WHEN 'BUSINESS' THEN 'BUSINESS'
         WHEN 'PRO' THEN 'PRO'
         WHEN 'SCALE' THEN 'SCALE'
         WHEN 'ENTERPRISE' THEN 'ENTERPRISE'
         WHEN 'DEDICATED CLOUD' THEN 'DEDICATED'
         WHEN 'DEDICATED' THEN 'DEDICATED'
         WHEN 'HYBRID ENTERPRISE' THEN 'HYBRID'
         WHEN 'HYBRID' THEN 'HYBRID'
         ELSE 'CUSTOM'
       END),
       contract_term = COALESCE(NULLIF(contract_term,''), 'MONTHLY');

ALTER TABLE platform_companies ALTER COLUMN plan_code SET DEFAULT 'CUSTOM';
ALTER TABLE platform_companies ALTER COLUMN contract_term SET DEFAULT 'MONTHLY';
ALTER TABLE platform_companies ALTER COLUMN plan_code SET NOT NULL;
ALTER TABLE platform_companies ALTER COLUMN contract_term SET NOT NULL;

DO $$ BEGIN
  ALTER TABLE platform_companies ADD CONSTRAINT ck_platform_company_contract_term
    CHECK (contract_term IN ('MONTHLY','SEMIANNUAL','ANNUAL'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
