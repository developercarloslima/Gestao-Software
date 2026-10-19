-- Tenant universal: estrutura exclusivamente; não importa dados históricos da NH.
-- Origem técnica: NH v83.126 V96__inspection_fipe_version_selection.sql. V1-V4 preservadas.
-- v83.126: version selection independent from signed historical data.
ALTER TABLE quotations ADD COLUMN IF NOT EXISTS vehicle_fipe_code VARCHAR(24);
ALTER TABLE quotations ADD COLUMN IF NOT EXISTS vehicle_fipe_reference VARCHAR(70);
ALTER TABLE quotations ADD COLUMN IF NOT EXISTS vehicle_fipe_source VARCHAR(40);
CREATE TABLE inspection_pending_fipe_selection (
    inspection_id UUID PRIMARY KEY REFERENCES inspection_requests(id) ON DELETE CASCADE,
    price_id BIGINT NOT NULL REFERENCES fipe_dataset_prices(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
