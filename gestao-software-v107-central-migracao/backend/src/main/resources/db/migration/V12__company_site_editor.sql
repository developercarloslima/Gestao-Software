-- Conteudo e imagens exclusivos do Site Principal de cada empresa.
CREATE TABLE IF NOT EXISTS platform_company_site_settings (
  company_id UUID PRIMARY KEY REFERENCES platform_companies(id) ON DELETE CASCADE,
  config_json TEXT NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS platform_company_site_assets (
  id UUID PRIMARY KEY,
  company_id UUID NOT NULL REFERENCES platform_companies(id) ON DELETE CASCADE,
  original_name VARCHAR(180) NOT NULL,
  content_type VARCHAR(60) NOT NULL,
  content_bytes BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_company_site_assets_company ON platform_company_site_assets(company_id);
