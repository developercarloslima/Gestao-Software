-- Preserva o status anterior para que uma empresa em teste retorne a TESTE
-- após uma suspensão. Não exclui dados financeiros nem dados de tenants.
ALTER TABLE platform_companies
  ADD COLUMN IF NOT EXISTS status_before_suspend VARCHAR(30);

ALTER TABLE platform_companies
  ADD CONSTRAINT ck_platform_company_status_before_suspend
  CHECK (status_before_suspend IS NULL OR status_before_suspend IN ('ACTIVE', 'TRIAL'));
