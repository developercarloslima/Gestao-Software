-- Novos recursos: análise de capacidade, pagamentos verificados e fila de implantação.
-- Não altera histórico nem migrações já executadas.
CREATE TABLE IF NOT EXISTS gs_company_capacity (
  company_id UUID PRIMARY KEY REFERENCES platform_companies(id) ON DELETE CASCADE,
  input_json TEXT NOT NULL,
  estimation_json TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS gs_verified_payment (
  invoice_id UUID PRIMARY KEY REFERENCES platform_company_invoices(id) ON DELETE RESTRICT,
  provider VARCHAR(30) NOT NULL,
  provider_payment_id VARCHAR(150) NOT NULL UNIQUE,
  provider_status VARCHAR(40) NOT NULL,
  verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS gs_deployments (
  id UUID PRIMARY KEY,
  company_id UUID NOT NULL REFERENCES platform_companies(id) ON DELETE RESTRICT,
  provider VARCHAR(30) NOT NULL,
  target_vps_id UUID REFERENCES platform_vps(id) ON DELETE RESTRICT,
  status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
  requested_host VARCHAR(240) NOT NULL,
  deployment_version VARCHAR(80) NOT NULL DEFAULT 'nh-tenant-base-v1',
  app_url VARCHAR(500),
  api_url VARCHAR(500),
  external_id VARCHAR(180),
  last_message VARCHAR(1000),
  requested_by VARCHAR(120),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  claimed_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_gs_deploy_status CHECK (status IN ('QUEUED','PROVISIONING','VERIFYING','ACTIVE','FAILED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_gs_active_deployment ON gs_deployments(company_id)
  WHERE status IN ('QUEUED','PROVISIONING','VERIFYING','ACTIVE');
CREATE INDEX IF NOT EXISTS idx_gs_deploy_queue ON gs_deployments(status,created_at);
CREATE TABLE IF NOT EXISTS gs_deployment_events (
  id UUID PRIMARY KEY,
  deployment_id UUID NOT NULL REFERENCES gs_deployments(id) ON DELETE CASCADE,
  event VARCHAR(40) NOT NULL,
  message VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS gs_invoice_checkout (
 invoice_id UUID PRIMARY KEY REFERENCES platform_company_invoices(id) ON DELETE CASCADE,
 provider_customer_id VARCHAR(100),
 provider_payment_id VARCHAR(150),
 status VARCHAR(30) NOT NULL DEFAULT 'CREATING',
 invoice_url VARCHAR(800),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
