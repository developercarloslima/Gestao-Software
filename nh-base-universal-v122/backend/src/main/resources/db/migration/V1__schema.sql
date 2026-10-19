CREATE TABLE vehicle_categories (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE plans (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    subtitle VARCHAR(180),
    category_id BIGINT NOT NULL REFERENCES vehicle_categories(id),
    region VARCHAR(20) NOT NULL,
    display_order INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    extra_above NUMERIC(14,2),
    extra_step NUMERIC(14,2),
    extra_increment NUMERIC(14,2),
    extra_base_price NUMERIC(14,2)
);

CREATE TABLE coverages (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(180) NOT NULL
);

CREATE TABLE plan_coverages (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    coverage_id BIGINT NOT NULL REFERENCES coverages(id),
    status VARCHAR(20) NOT NULL,
    detail VARCHAR(240),
    sort_order INTEGER NOT NULL,
    UNIQUE(plan_id, coverage_id)
);

CREATE TABLE price_ranges (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    min_value NUMERIC(14,2) NOT NULL,
    max_value NUMERIC(14,2) NOT NULL,
    monthly_price NUMERIC(14,2) NOT NULL,
    CONSTRAINT chk_price_range CHECK (max_value >= min_value),
    UNIQUE(plan_id, min_value, max_value)
);

CREATE INDEX idx_price_ranges_lookup ON price_ranges(plan_id, min_value, max_value);

CREATE TABLE quotations (
    id UUID PRIMARY KEY,
    quote_number VARCHAR(30) NOT NULL UNIQUE,
    consultant_name VARCHAR(120) NOT NULL,
    customer_name VARCHAR(120) NOT NULL,
    whatsapp VARCHAR(30),
    plate VARCHAR(10) NOT NULL,
    model VARCHAR(120) NOT NULL,
    manufacture_year INTEGER NOT NULL,
    fipe_value NUMERIC(14,2) NOT NULL,
    category_code VARCHAR(50) NOT NULL,
    region VARCHAR(20) NOT NULL,
    selected_plan_code VARCHAR(80) NOT NULL,
    selected_plan_name VARCHAR(120) NOT NULL,
    monthly_value NUMERIC(14,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ
);

CREATE INDEX idx_quotations_created_at ON quotations(created_at DESC);
CREATE INDEX idx_quotations_plate ON quotations(plate);
