-- Tenant universal: estrutura exclusivamente; não importa dados históricos da NH.
-- Origem técnica: NH v83.126 V95__fipe_dataset_snapshots.sql. V1-V4 preservadas.
-- v83.125: FIPE snapshots from public fipex-labs/dataset. No legacy quotation is updated.
CREATE TABLE fipe_dataset_imports (
    id BIGSERIAL PRIMARY KEY,
    release_tag VARCHAR(40) NOT NULL,
    reference_year INTEGER NOT NULL CHECK (reference_year BETWEEN 1990 AND 2100),
    reference_month INTEGER NOT NULL CHECK (reference_month BETWEEN 1 AND 12),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    source_url TEXT NOT NULL,
    rows_imported INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    error_message VARCHAR(500)
);
CREATE UNIQUE INDEX uq_fipe_dataset_complete_release ON fipe_dataset_imports (release_tag) WHERE status='COMPLETED';
CREATE INDEX idx_fipe_dataset_recent ON fipe_dataset_imports (reference_year DESC,reference_month DESC,id DESC) WHERE status='COMPLETED';

CREATE TABLE fipe_dataset_prices (
    id BIGSERIAL PRIMARY KEY,
    import_id BIGINT NOT NULL REFERENCES fipe_dataset_imports(id) ON DELETE CASCADE,
    vehicle_type VARCHAR(40) NOT NULL,
    fipe_code VARCHAR(24) NOT NULL,
    model_name VARCHAR(240) NOT NULL,
    brand_name VARCHAR(130) NOT NULL,
    fuel_name VARCHAR(80),
    fuel_code VARCHAR(8) NOT NULL DEFAULT '',
    model_year INTEGER NOT NULL,
    zero_km BOOLEAN NOT NULL DEFAULT FALSE,
    value_cents BIGINT NOT NULL CHECK (value_cents > 0),
    UNIQUE(import_id, vehicle_type, fipe_code, model_year, zero_km, fuel_code)
);
CREATE INDEX idx_fipe_prices_search_model ON fipe_dataset_prices(import_id, brand_name, model_name);
CREATE INDEX idx_fipe_prices_search_fipe ON fipe_dataset_prices(import_id, fipe_code);
CREATE INDEX idx_fipe_prices_category_year ON fipe_dataset_prices(import_id, vehicle_type, model_year);
