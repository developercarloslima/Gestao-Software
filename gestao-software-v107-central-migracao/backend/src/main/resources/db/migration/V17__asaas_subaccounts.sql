-- Uma subconta Asaas por empresa-cliente, com carteira e credenciais isoladas.
-- A chave retornada pelo Asaas nunca deve ser persistida em texto puro.
CREATE TABLE IF NOT EXISTS platform_asaas_subaccounts (
    company_id UUID PRIMARY KEY REFERENCES platform_companies(id) ON DELETE RESTRICT,
    asaas_account_id VARCHAR(160) UNIQUE,
    wallet_id VARCHAR(160) UNIQUE,
    encrypted_api_key TEXT,
    owner_email VARCHAR(180) NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'CREATING'
        CHECK (state IN ('CREATING','REJECTED','REVIEW_REQUIRED','PENDING_ACTIVATION','ACTIVE')),
    requested_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_asaas_subaccounts_state
    ON platform_asaas_subaccounts(state, updated_at DESC);
