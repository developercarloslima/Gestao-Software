-- V86 - Gestão comercial/financeira: associados, veículos, cobranças, carteira de consultor e saques.
-- Não remove nem recria dados existentes.

ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS commission_mode VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',
    ADD COLUMN IF NOT EXISTS commission_value NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS payout_pix_key_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS payout_pix_key VARCHAR(255);

ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_commission_mode;
ALTER TABLE consultants ADD CONSTRAINT ck_consultants_commission_mode
    CHECK (commission_mode IN ('PERCENTAGE', 'FIXED'));
ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_commission_value;
ALTER TABLE consultants ADD CONSTRAINT ck_consultants_commission_value
    CHECK (commission_value >= 0 AND (commission_mode <> 'PERCENTAGE' OR commission_value <= 100));
ALTER TABLE consultants DROP CONSTRAINT IF EXISTS ck_consultants_payout_pix_key_type;
ALTER TABLE consultants ADD CONSTRAINT ck_consultants_payout_pix_key_type
    CHECK (payout_pix_key_type IS NULL OR payout_pix_key_type IN ('CPF','CNPJ','EMAIL','PHONE','EVP'));

CREATE TABLE IF NOT EXISTS associates (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    normalized_name VARCHAR(180) NOT NULL,
    document VARCHAR(14),
    whatsapp VARCHAR(30),
    email VARCHAR(180),
    asaas_customer_id VARCHAR(80),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_associates_document_not_null
    ON associates(document) WHERE document IS NOT NULL AND document <> '';
CREATE UNIQUE INDEX IF NOT EXISTS ux_associates_asaas_customer_not_null
    ON associates(asaas_customer_id) WHERE asaas_customer_id IS NOT NULL AND asaas_customer_id <> '';
CREATE INDEX IF NOT EXISTS idx_associates_normalized_name ON associates(normalized_name);

CREATE TABLE IF NOT EXISTS commercial_vehicles (
    id UUID PRIMARY KEY,
    associate_id UUID NOT NULL REFERENCES associates(id) ON DELETE RESTRICT,
    consultant_id UUID REFERENCES consultants(id) ON DELETE SET NULL,
    quotation_id UUID REFERENCES quotations(id) ON DELETE SET NULL,
    plate VARCHAR(10),
    model VARCHAR(140) NOT NULL,
    manufacture_year INTEGER,
    selected_plan_code VARCHAR(80),
    selected_plan_name VARCHAR(140),
    monthly_value NUMERIC(14,2) NOT NULL DEFAULT 0,
    adhesion_value NUMERIC(14,2) NOT NULL DEFAULT 0,
    billing_due_day INTEGER,
    first_billing_due_date DATE,
    adhesion_payment_method VARCHAR(20) NOT NULL DEFAULT 'BOLETO',
    recurring_billing_type VARCHAR(20) NOT NULL DEFAULT 'BOLETO',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    asaas_subscription_id VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_vehicles_quotation_not_null
    ON commercial_vehicles(quotation_id) WHERE quotation_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_commercial_vehicles_subscription_not_null
    ON commercial_vehicles(asaas_subscription_id) WHERE asaas_subscription_id IS NOT NULL AND asaas_subscription_id <> '';
CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_associate ON commercial_vehicles(associate_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_consultant ON commercial_vehicles(consultant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_plate ON commercial_vehicles(plate);
ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_adhesion_method;
ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_adhesion_method
    CHECK (adhesion_payment_method IN ('CASH_HAND','BOLETO','PIX'));
ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_recurring_type;
ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_recurring_type
    CHECK (recurring_billing_type IN ('BOLETO','PIX'));
ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_status;
ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_status
    CHECK (status IN ('DRAFT','ACTIVE','CANCELLED'));

CREATE TABLE IF NOT EXISTS financial_charges (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES commercial_vehicles(id) ON DELETE CASCADE,
    associate_id UUID NOT NULL REFERENCES associates(id) ON DELETE RESTRICT,
    consultant_id UUID REFERENCES consultants(id) ON DELETE SET NULL,
    provider VARCHAR(20) NOT NULL DEFAULT 'ASAAS',
    provider_payment_id VARCHAR(100),
    provider_subscription_id VARCHAR(100),
    charge_type VARCHAR(20) NOT NULL,
    billing_type VARCHAR(20) NOT NULL,
    value NUMERIC(14,2) NOT NULL,
    due_date DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMPTZ,
    invoice_url VARCHAR(700),
    bank_slip_url VARCHAR(700),
    identification_field VARCHAR(120),
    bar_code VARCHAR(120),
    pix_payload TEXT,
    pix_encoded_image TEXT,
    external_reference VARCHAR(180),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_financial_charges_provider_payment
    ON financial_charges(provider_payment_id) WHERE provider_payment_id IS NOT NULL AND provider_payment_id <> '';
CREATE INDEX IF NOT EXISTS idx_financial_charges_vehicle ON financial_charges(vehicle_id, due_date DESC, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_financial_charges_consultant ON financial_charges(consultant_id, paid_at DESC);
CREATE INDEX IF NOT EXISTS idx_financial_charges_subscription ON financial_charges(provider_subscription_id);
ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_value;
ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_value CHECK (value > 0);
ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_status;
ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_status
    CHECK (status IN ('PENDING','CONFIRMED','RECEIVED','OVERDUE','REFUNDED','CANCELLED','ERROR'));
ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_type;
ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_type
    CHECK (charge_type IN ('ADHESION','CLOSING','MONTHLY'));
ALTER TABLE financial_charges DROP CONSTRAINT IF EXISTS ck_financial_charges_billing_type;
ALTER TABLE financial_charges ADD CONSTRAINT ck_financial_charges_billing_type
    CHECK (billing_type IN ('CASH_HAND','BOLETO','PIX'));

CREATE TABLE IF NOT EXISTS consultant_withdrawals (
    id UUID PRIMARY KEY,
    consultant_id UUID NOT NULL REFERENCES consultants(id) ON DELETE RESTRICT,
    amount NUMERIC(14,2) NOT NULL,
    pix_key_type VARCHAR(20) NOT NULL,
    pix_key VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
    provider_transfer_id VARCHAR(100),
    fail_reason VARCHAR(700),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_consultant_withdrawals_provider_transfer
    ON consultant_withdrawals(provider_transfer_id) WHERE provider_transfer_id IS NOT NULL AND provider_transfer_id <> '';
CREATE INDEX IF NOT EXISTS idx_consultant_withdrawals_consultant ON consultant_withdrawals(consultant_id, created_at DESC);
ALTER TABLE consultant_withdrawals DROP CONSTRAINT IF EXISTS ck_consultant_withdrawal_amount;
ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_amount CHECK (amount > 0);
ALTER TABLE consultant_withdrawals DROP CONSTRAINT IF EXISTS ck_consultant_withdrawal_status;
ALTER TABLE consultant_withdrawals ADD CONSTRAINT ck_consultant_withdrawal_status
    CHECK (status IN ('REQUESTED','PROCESSING','PAID','FAILED','CANCELLED'));

CREATE TABLE IF NOT EXISTS consultant_wallet_movements (
    id UUID PRIMARY KEY,
    consultant_id UUID NOT NULL REFERENCES consultants(id) ON DELETE RESTRICT,
    vehicle_id UUID REFERENCES commercial_vehicles(id) ON DELETE SET NULL,
    charge_id UUID REFERENCES financial_charges(id) ON DELETE SET NULL,
    withdrawal_id UUID REFERENCES consultant_withdrawals(id) ON DELETE SET NULL,
    movement_type VARCHAR(20) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    locked BOOLEAN NOT NULL DEFAULT TRUE,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    unlocked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_wallet_consultant ON consultant_wallet_movements(consultant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_charge ON consultant_wallet_movements(charge_id);
CREATE INDEX IF NOT EXISTS idx_wallet_withdrawal ON consultant_wallet_movements(withdrawal_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_wallet_charge_movement
    ON consultant_wallet_movements(charge_id, movement_type) WHERE charge_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_wallet_withdrawal_movement
    ON consultant_wallet_movements(withdrawal_id, movement_type) WHERE withdrawal_id IS NOT NULL;
ALTER TABLE consultant_wallet_movements DROP CONSTRAINT IF EXISTS ck_wallet_movement_type;
ALTER TABLE consultant_wallet_movements ADD CONSTRAINT ck_wallet_movement_type
    CHECK (movement_type IN ('COMMISSION','WITHDRAWAL','REVERSAL'));

CREATE TABLE IF NOT EXISTS asaas_webhook_events (
    event_id VARCHAR(120) PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    processing_error VARCHAR(1000)
);

-- Migração conservadora do histórico: cria um associado por CPF já conhecido.
INSERT INTO associates(id, name, normalized_name, document, whatsapp, active, created_at, updated_at)
SELECT gen_random_uuid(), q.customer_name,
       lower(trim(regexp_replace(translate(upper(q.customer_name),
         'ÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑ',
         'AAAAAEEEEIIIIOOOOOUUUUCN'), '[^A-Za-z0-9]+', ' ', 'g'))),
       regexp_replace(q.customer_cpf, '[^0-9]', '', 'g'),
       max(q.whatsapp), TRUE, min(q.created_at), max(q.updated_at)
FROM quotations q
WHERE q.customer_cpf IS NOT NULL
  AND length(regexp_replace(q.customer_cpf, '[^0-9]', '', 'g')) IN (11,14)
GROUP BY q.customer_name, regexp_replace(q.customer_cpf, '[^0-9]', '', 'g')
ON CONFLICT DO NOTHING;

-- Cria a ficha do veículo para cotações existentes que já puderam ser ligadas por CPF.
INSERT INTO commercial_vehicles(
    id, associate_id, consultant_id, quotation_id, plate, model, manufacture_year,
    selected_plan_code, selected_plan_name, monthly_value, adhesion_value,
    billing_due_day, first_billing_due_date, status, created_at, updated_at
)
SELECT gen_random_uuid(), a.id, q.consultant_id, q.id, q.plate, q.model, q.manufacture_year,
       q.selected_plan_code, q.selected_plan_name, q.monthly_value, q.one_time_fee,
       q.billing_due_day, q.first_billing_due_date,
       CASE WHEN q.status = 'ACCEPTED' THEN 'ACTIVE' ELSE 'DRAFT' END,
       q.created_at, q.updated_at
FROM quotations q
JOIN associates a ON a.document = regexp_replace(q.customer_cpf, '[^0-9]', '', 'g')
WHERE q.customer_cpf IS NOT NULL
ON CONFLICT DO NOTHING;
