-- V5 - Cadastro comercial completo: forma de pagamento, arquivos de identidade visual e contratos.

ALTER TABLE platform_companies ADD COLUMN IF NOT EXISTS payment_method VARCHAR(40);
UPDATE platform_companies SET payment_method = COALESCE(NULLIF(payment_method,''), 'BOLETO');
ALTER TABLE platform_companies ALTER COLUMN payment_method SET DEFAULT 'BOLETO';
ALTER TABLE platform_companies ALTER COLUMN payment_method SET NOT NULL;

DO $$ BEGIN
  ALTER TABLE platform_companies ADD CONSTRAINT ck_platform_company_payment_method
    CHECK (payment_method IN ('PIX_AUTOMATICO','DEBITO_AUTOMATICO','BOLETO'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS platform_company_files (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES platform_companies(id) ON DELETE CASCADE,
    purpose VARCHAR(40) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    content BYTEA NOT NULL,
    file_size BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_platform_company_file_purpose CHECK (purpose IN ('LOGO','VISUAL_IDENTITY_PDF')),
    CONSTRAINT ck_platform_company_file_size CHECK (file_size > 0)
);
CREATE INDEX IF NOT EXISTS idx_platform_company_files_company ON platform_company_files(company_id, purpose, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ux_platform_company_file_purpose ON platform_company_files(company_id, purpose);

CREATE TABLE IF NOT EXISTS platform_company_contracts (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES platform_companies(id) ON DELETE CASCADE,
    contract_number VARCHAR(80) NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_SIGNATURES',
    plan_code VARCHAR(40) NOT NULL,
    plan_name VARCHAR(120) NOT NULL,
    contract_term VARCHAR(20) NOT NULL,
    monthly_fee NUMERIC(14,2) NOT NULL,
    payment_method VARCHAR(40) NOT NULL,
    modules_snapshot TEXT NOT NULL,
    company_snapshot TEXT NOT NULL,
    client_sign_token UUID NOT NULL UNIQUE,
    client_signed_by VARCHAR(180),
    client_signed_document VARCHAR(20),
    client_signed_at TIMESTAMPTZ,
    management_signed_by VARCHAR(160),
    management_signed_username VARCHAR(120),
    management_signed_at TIMESTAMPTZ,
    effective_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_platform_contract_status CHECK (status IN ('PENDING_SIGNATURES','PARTIALLY_SIGNED','SIGNED','CANCELLED')),
    CONSTRAINT ck_platform_contract_fee CHECK (monthly_fee >= 0)
);
CREATE INDEX IF NOT EXISTS idx_platform_contract_company ON platform_company_contracts(company_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_platform_contract_status ON platform_company_contracts(status, created_at DESC);

-- Gera o primeiro contrato para empresas já existentes antes da V5.
INSERT INTO platform_company_contracts(
    id, company_id, contract_number, status, plan_code, plan_name, contract_term, monthly_fee, payment_method,
    modules_snapshot, company_snapshot, client_sign_token, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    c.id,
    'GS-' || TO_CHAR(CURRENT_DATE,'YYYY') || '-' || UPPER(SUBSTRING(REPLACE(gen_random_uuid()::text,'-','') FROM 1 FOR 8)),
    'PENDING_SIGNATURES',
    c.plan_code,
    c.plan_name,
    c.contract_term,
    c.monthly_fee,
    c.payment_method,
    COALESCE(NULLIF(STRING_AGG(m.name, '; ' ORDER BY m.sort_order, m.name) FILTER (WHERE pcm.enabled=TRUE), ''), 'Nenhum módulo habilitado'),
    'Razão social: ' || COALESCE(c.legal_name,'') ||
      ' | Nome fantasia: ' || COALESCE(c.trade_name,'') ||
      ' | CPF/CNPJ: ' || COALESCE(c.document,'') ||
      ' | Contato: ' || COALESCE(c.contact_name,'') ||
      ' | E-mail: ' || COALESCE(c.contact_email,''),
    gen_random_uuid(),
    NOW(), NOW()
FROM platform_companies c
LEFT JOIN platform_company_modules pcm ON pcm.company_id=c.id
LEFT JOIN platform_modules m ON m.code=pcm.module_code
WHERE NOT EXISTS (SELECT 1 FROM platform_company_contracts pc WHERE pc.company_id=c.id)
GROUP BY c.id, c.plan_code, c.plan_name, c.contract_term, c.monthly_fee, c.payment_method,
         c.legal_name, c.trade_name, c.document, c.contact_name, c.contact_email;
