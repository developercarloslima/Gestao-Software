-- Vencimento mensal escolhido na cotação.
-- O primeiro vencimento deve estar entre 30 e 40 dias após a emissão
-- e somente nos dias comerciais 5, 10, 15, 20, 25 ou 30.
ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS billing_due_day INTEGER,
    ADD COLUMN IF NOT EXISTS first_billing_due_date DATE;

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS ck_quotations_billing_due_day;

ALTER TABLE quotations
    ADD CONSTRAINT ck_quotations_billing_due_day
    CHECK (billing_due_day IS NULL OR billing_due_day IN (5, 10, 15, 20, 25, 30));

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS ck_quotations_billing_due_pair;

ALTER TABLE quotations
    ADD CONSTRAINT ck_quotations_billing_due_pair
    CHECK (
        (billing_due_day IS NULL AND first_billing_due_date IS NULL)
        OR
        (billing_due_day IS NOT NULL AND first_billing_due_date IS NOT NULL)
    );
