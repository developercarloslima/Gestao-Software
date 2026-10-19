ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS zero_km BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN quotations.zero_km IS 'Indica se o veículo informado na cotação é zero quilômetro.';
