CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS gs_users (
    username VARCHAR(120) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS platform_modules (
    code VARCHAR(60) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS platform_companies (
    id UUID PRIMARY KEY,
    group_name VARCHAR(160),
    legal_name VARCHAR(200) NOT NULL,
    trade_name VARCHAR(180) NOT NULL,
    document VARCHAR(20),
    contact_name VARCHAR(160),
    contact_email VARCHAR(180),
    contact_phone VARCHAR(40),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    plan_name VARCHAR(120) NOT NULL DEFAULT 'Personalizado',
    monthly_fee NUMERIC(14,2) NOT NULL DEFAULT 0,
    billing_due_day INTEGER NOT NULL DEFAULT 10,
    contract_start_date DATE NOT NULL DEFAULT CURRENT_DATE,
    contract_end_date DATE,
    platform_display_name VARCHAR(180),
    platform_legal_name VARCHAR(180) NOT NULL DEFAULT 'Gestão Software LTDA',
    logo_url VARCHAR(1000),
    favicon_url VARCHAR(1000),
    primary_color VARCHAR(20) NOT NULL DEFAULT '#1264f4',
    secondary_color VARCHAR(20) NOT NULL DEFAULT '#06164a',
    accent_color VARCHAR(20) NOT NULL DEFAULT '#dcecff',
    site_title VARCHAR(180),
    company_tagline VARCHAR(220),
    login_subtitle VARCHAR(220),
    default_module_landing VARCHAR(80),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_platform_company_status CHECK (status IN ('TRIAL','ACTIVE','SUSPENDED','CANCELLED')),
    CONSTRAINT ck_platform_company_monthly_fee CHECK (monthly_fee >= 0),
    CONSTRAINT ck_platform_company_due_day CHECK (billing_due_day BETWEEN 1 AND 28)
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_platform_company_document ON platform_companies(document) WHERE document IS NOT NULL AND document <> '';
CREATE INDEX IF NOT EXISTS idx_platform_company_status ON platform_companies(status, trade_name);
CREATE INDEX IF NOT EXISTS idx_platform_company_group ON platform_companies(group_name);

CREATE TABLE IF NOT EXISTS platform_company_modules (
    company_id UUID NOT NULL REFERENCES platform_companies(id) ON DELETE CASCADE,
    module_code VARCHAR(60) NOT NULL REFERENCES platform_modules(code) ON DELETE RESTRICT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    monthly_price NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (company_id, module_code),
    CONSTRAINT ck_platform_company_module_price CHECK (monthly_price >= 0)
);

CREATE TABLE IF NOT EXISTS platform_company_invoices (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES platform_companies(id) ON DELETE RESTRICT,
    reference_month DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMPTZ,
    notes VARCHAR(600),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_platform_invoice_amount CHECK (amount >= 0),
    CONSTRAINT ck_platform_invoice_status CHECK (status IN ('PENDING','PAID','OVERDUE','CANCELLED')),
    CONSTRAINT uq_platform_invoice_company_month UNIQUE (company_id, reference_month)
);
CREATE INDEX IF NOT EXISTS idx_platform_invoice_month_status ON platform_company_invoices(reference_month, status, due_date);
CREATE INDEX IF NOT EXISTS idx_platform_invoice_company ON platform_company_invoices(company_id, reference_month DESC);

CREATE TABLE IF NOT EXISTS platform_operating_expenses (
    id UUID PRIMARY KEY,
    reference_month DATE NOT NULL,
    category VARCHAR(80) NOT NULL,
    description VARCHAR(240) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    notes VARCHAR(600),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_platform_expense_amount CHECK (amount >= 0)
);
CREATE INDEX IF NOT EXISTS idx_platform_expense_month ON platform_operating_expenses(reference_month, created_at DESC);

CREATE TABLE IF NOT EXISTS platform_partners (
    code VARCHAR(40) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    profit_share_percent NUMERIC(5,2) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_platform_partner_share CHECK (profit_share_percent >= 0 AND profit_share_percent <= 100)
);

CREATE TABLE IF NOT EXISTS platform_vps (
    id UUID PRIMARY KEY,
    name VARCHAR(140) NOT NULL,
    provider VARCHAR(120) NOT NULL,
    plan_name VARCHAR(160),
    hostname VARCHAR(200),
    ip_address VARCHAR(80),
    monthly_cost NUMERIC(14,2) NOT NULL DEFAULT 0,
    max_companies INTEGER NOT NULL DEFAULT 10,
    cpu_cores NUMERIC(8,2),
    ram_gb NUMERIC(10,2),
    storage_gb NUMERIC(12,2),
    cpu_usage_percent NUMERIC(5,2),
    ram_usage_percent NUMERIC(5,2),
    storage_usage_percent NUMERIC(5,2),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    renewal_date DATE,
    last_metrics_at TIMESTAMPTZ,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_platform_vps_cost CHECK (monthly_cost >= 0),
    CONSTRAINT ck_platform_vps_max_companies CHECK (max_companies > 0),
    CONSTRAINT ck_platform_vps_cpu_usage CHECK (cpu_usage_percent IS NULL OR (cpu_usage_percent >= 0 AND cpu_usage_percent <= 100)),
    CONSTRAINT ck_platform_vps_ram_usage CHECK (ram_usage_percent IS NULL OR (ram_usage_percent >= 0 AND ram_usage_percent <= 100)),
    CONSTRAINT ck_platform_vps_storage_usage CHECK (storage_usage_percent IS NULL OR (storage_usage_percent >= 0 AND storage_usage_percent <= 100))
);
CREATE INDEX IF NOT EXISTS idx_platform_vps_active ON platform_vps(active, name);

CREATE TABLE IF NOT EXISTS platform_company_hosting (
    company_id UUID PRIMARY KEY REFERENCES platform_companies(id) ON DELETE CASCADE,
    vps_id UUID REFERENCES platform_vps(id) ON DELETE SET NULL,
    environment_name VARCHAR(120),
    domain VARCHAR(240),
    subdomain VARCHAR(240),
    dns_mode VARCHAR(30) NOT NULL DEFAULT 'SUBDOMAIN',
    app_url VARCHAR(500),
    api_url VARCHAR(500),
    database_name VARCHAR(160),
    deployment_path VARCHAR(500),
    deployment_method VARCHAR(120),
    hosting_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_company_hosting_vps ON platform_company_hosting(vps_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_platform_hosting_domain ON platform_company_hosting(LOWER(domain)) WHERE domain IS NOT NULL AND domain <> '';
CREATE UNIQUE INDEX IF NOT EXISTS ux_platform_hosting_subdomain ON platform_company_hosting(LOWER(subdomain)) WHERE subdomain IS NOT NULL AND subdomain <> '';
