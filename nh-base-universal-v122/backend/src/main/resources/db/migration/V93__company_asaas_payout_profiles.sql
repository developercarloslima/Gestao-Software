-- Preparação conservadora do destino de repasse por empresa (um banco por instalação).
-- Sem débitos, transferências, credenciais ou alterações de histórico.
CREATE TABLE IF NOT EXISTS finance_company_payout_profile (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    destination_type VARCHAR(8) NOT NULL CHECK (destination_type IN ('PIX','BANK')),
    encrypted_destination TEXT NOT NULL,
    masked_destination VARCHAR(140) NOT NULL,
    auto_daily_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    owner_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    minimum_reserve NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (minimum_reserve >= 0),
    transfer_fee_estimate NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (transfer_fee_estimate >= 0),
    updated_by VARCHAR(160),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS finance_company_payout_batches (
    id UUID PRIMARY KEY,
    run_date DATE NOT NULL UNIQUE,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    estimated_fee NUMERIC(14,2) NOT NULL CHECK (estimated_fee >= 0),
    reserved_amount NUMERIC(14,2) NOT NULL CHECK (reserved_amount >= 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('SUBMITTING','PROCESSING','DONE','FAILED','CANCELLED','UNCERTAIN')),
    provider_transfer_id VARCHAR(100) UNIQUE,
    external_reference VARCHAR(160) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
