-- V92 - Reconciliacao de esquema nao destrutiva para bancos com historico anterior.
-- Mantem as migracoes historicas V87/V88 e nao reinicializa o catalogo editado pelo ADM.
-- Em instalacoes novas, V79/V80/V81/V82/V84 executam normalmente; aqui os DDLs sao idempotentes.

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS preexisting_damage BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS preexisting_damage_details TEXT;

ALTER TABLE coverages
    ADD COLUMN IF NOT EXISTS optional_vehicle_categories VARCHAR(1000) NOT NULL DEFAULT '';

ALTER TABLE plan_coverages
    ADD COLUMN IF NOT EXISTS damage_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS event_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS towing_radius_km INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_plan_coverage_damage_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_damage_condition
            CHECK (damage_condition IS NULL OR damage_condition IN ('ANY_DAMAGE','TOTAL_ONLY','PARTIAL_ONLY'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_plan_coverage_event_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_event_condition
            CHECK (event_condition IS NULL OR event_condition IN ('ANY_ORIGIN','COLLISION_DERIVED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='chk_plan_coverage_towing_radius') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_towing_radius
            CHECK (towing_radius_km IS NULL OR towing_radius_km >= 0);
    END IF;
END $$;

-- KEEP e suportado pelo painel de regras editaveis. A alteracao e idempotente.
ALTER TABLE discount_rule_benefits DROP CONSTRAINT IF EXISTS chk_discount_rule_benefit_action;
ALTER TABLE discount_rule_benefits ADD CONSTRAINT chk_discount_rule_benefit_action
    CHECK (action IN ('KEEP','REMOVE','FORCE_INCLUDE'));

-- Adicionais novos sao apenas criados quando ausentes. Nenhuma preferencia de plano e sobrescrita.
INSERT INTO coverages(code,name,optional_vehicle_categories) VALUES
    ('THIRD_PARTY_ADD_7K', 'Adicional de R$ 7 mil para danos a terceiros',
      'MOTORCYCLE_OVER_300,MOTORCYCLE_PROMO_2026,MOTORCYCLE_UP_TO_300'),
    ('THIRD_PARTY_ADD_10K', 'Adicional de R$ 10 mil para danos a terceiros',
      'MOTORCYCLE_OVER_300,MOTORCYCLE_PROMO_2026,MOTORCYCLE_UP_TO_300'),
    ('THIRD_PARTY_ADD_50K', 'Adicional de R$ 50 mil para danos a terceiros',
      'CAR_IMPORTED,CAR_NATIONAL,TRUCK,UTILITY'),
    ('FALL','Queda',''),
    ('ROLLOVER','Capotamento','')
ON CONFLICT (code) DO NOTHING;

-- Em bancos antigos, preserva completamente os demais status, limites e precos do ADM.
-- Os vinculos ausentes dos novos opcionais sao oferecidos somente quando nao existem.
INSERT INTO plan_coverages(plan_id,coverage_id,status,detail,monthly_price,sort_order)
SELECT p.id,c.id,'OPTIONAL',o.detail,o.price,o.ord
FROM plans p
JOIN vehicle_categories vc ON vc.id=p.category_id
JOIN (VALUES
  ('THIRD_PARTY_ADD_7K','MOTORCYCLE_UP_TO_300',5.00::numeric,70,'Adicional de R$ 7 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_7K','MOTORCYCLE_OVER_300',5.00::numeric,70,'Adicional de R$ 7 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_7K','MOTORCYCLE_PROMO_2026',5.00::numeric,70,'Adicional de R$ 7 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_10K','MOTORCYCLE_UP_TO_300',10.00::numeric,71,'Adicional de R$ 10 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_10K','MOTORCYCLE_OVER_300',10.00::numeric,71,'Adicional de R$ 10 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_10K','MOTORCYCLE_PROMO_2026',10.00::numeric,71,'Adicional de R$ 10 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_50K','CAR_NATIONAL',29.90::numeric,70,'Adicional de R$ 50 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_50K','CAR_IMPORTED',29.90::numeric,70,'Adicional de R$ 50 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_50K','UTILITY',29.90::numeric,70,'Adicional de R$ 50 mil para danos a terceiros'),
  ('THIRD_PARTY_ADD_50K','TRUCK',29.90::numeric,70,'Adicional de R$ 50 mil para danos a terceiros')
) AS o(code,vehicle_code,price,ord,detail)
  ON vc.code=o.vehicle_code
JOIN coverages c ON c.code=o.code
ON CONFLICT (plan_id,coverage_id) DO NOTHING;

-- A migracao V84 define o termo independente. O SQL abaixo e original e usa IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS inspection_responsibility_terms (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL REFERENCES inspection_requests(id) ON DELETE CASCADE,
    version INTEGER NOT NULL DEFAULT 1,
    public_token VARCHAR(160) NOT NULL UNIQUE,

    associate_name VARCHAR(140) NOT NULL,
    cpf VARCHAR(14) NOT NULL,
    whatsapp VARCHAR(30),
    plate VARCHAR(10),
    vehicle_model VARCHAR(120),
    model_year INTEGER,
    contracted_plan VARCHAR(160),
    inspection_created_at TIMESTAMPTZ,
    damage_details TEXT NOT NULL,

    created_by VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    whatsapp_sent_to VARCHAR(30),
    sent_at TIMESTAMPTZ,

    webauthn_registration_challenge VARCHAR(160),
    webauthn_registration_expires_at TIMESTAMPTZ,
    webauthn_origin VARCHAR(320),
    webauthn_rp_id VARCHAR(253),
    webauthn_credential_id VARCHAR(1024),
    webauthn_public_key BYTEA,
    webauthn_algorithm INTEGER,
    webauthn_sign_count BIGINT NOT NULL DEFAULT 0,
    webauthn_assertion_challenge VARCHAR(160),
    webauthn_assertion_expires_at TIMESTAMPTZ,

    acceptance_evidence_hash VARCHAR(64),
    term_pdf_sha256 VARCHAR(64),
    acceptance_device_metadata TEXT,
    acceptance_device_id VARCHAR(96),
    acceptance_ip VARCHAR(80),
    acceptance_latitude DOUBLE PRECISION,
    acceptance_longitude DOUBLE PRECISION,
    acceptance_accuracy_meters DOUBLE PRECISION,
    acceptance_location_captured_at TIMESTAMPTZ,
    acceptance_assertion_signature TEXT,
    acceptance_authenticator_data TEXT,
    acceptance_client_data_json TEXT,
    acceptance_proof_hash VARCHAR(64),
    acceptance_user_verified BOOLEAN,
    accepted_at TIMESTAMPTZ,

    CONSTRAINT uq_inspection_responsibility_term_version UNIQUE (inspection_id, version)
);

CREATE INDEX IF NOT EXISTS idx_inspection_responsibility_terms_inspection
    ON inspection_responsibility_terms(inspection_id, version DESC);

CREATE INDEX IF NOT EXISTS idx_inspection_responsibility_terms_accepted
    ON inspection_responsibility_terms(accepted_at);

COMMENT ON TABLE inspection_responsibility_terms IS
    'Termos independentes de responsabilidade por avaria pré-existente, com snapshot próprio e aceite WebAuthn separado do aceite do dossiê da vistoria.';
COMMENT ON COLUMN inspection_responsibility_terms.term_pdf_sha256 IS
    'SHA-256 do PDF isolado do termo apresentado ao associado no início da cerimônia WebAuthn.';
COMMENT ON COLUMN inspection_responsibility_terms.acceptance_proof_hash IS
    'Hash da prova WebAuthn do aceite exclusivo deste termo; não substitui nem altera o aceite principal da vistoria.';
