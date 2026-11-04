ALTER TABLE platform_companies
    ADD COLUMN IF NOT EXISTS financial_email VARCHAR(180);
