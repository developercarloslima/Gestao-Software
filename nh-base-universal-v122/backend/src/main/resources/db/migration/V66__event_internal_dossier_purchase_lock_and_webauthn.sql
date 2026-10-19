-- Separa o dossiê entregue ao associado do dossiê interno da operação,
-- registra o salvamento efetivo das compras e prepara o aceite WebAuthn do evento.

ALTER TABLE nh_event_purchase_items
    ADD COLUMN IF NOT EXISTS details_saved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS details_saved_by VARCHAR(180);

ALTER TABLE nh_event_records
    ADD COLUMN IF NOT EXISTS public_acceptance_token VARCHAR(160),
    ADD COLUMN IF NOT EXISTS webauthn_registration_challenge VARCHAR(160),
    ADD COLUMN IF NOT EXISTS webauthn_registration_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS webauthn_origin VARCHAR(320),
    ADD COLUMN IF NOT EXISTS webauthn_rp_id VARCHAR(253),
    ADD COLUMN IF NOT EXISTS webauthn_credential_id VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS webauthn_public_key BYTEA,
    ADD COLUMN IF NOT EXISTS webauthn_algorithm INTEGER,
    ADD COLUMN IF NOT EXISTS webauthn_sign_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS webauthn_assertion_challenge VARCHAR(160),
    ADD COLUMN IF NOT EXISTS webauthn_assertion_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS acceptance_evidence_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acceptance_dossier_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acceptance_device_metadata TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_ip VARCHAR(80),
    ADD COLUMN IF NOT EXISTS acceptance_latitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS acceptance_longitude DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS acceptance_accuracy_meters DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS acceptance_assertion_signature TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_authenticator_data TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_client_data_json TEXT,
    ADD COLUMN IF NOT EXISTS acceptance_proof_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acceptance_user_verified BOOLEAN,
    ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_nh_event_public_acceptance_token
    ON nh_event_records(public_acceptance_token)
    WHERE public_acceptance_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_nh_event_accepted_at
    ON nh_event_records(accepted_at);

COMMENT ON COLUMN nh_event_purchase_items.details_saved_at IS
    'Momento em que os dados comerciais da compra foram efetivamente salvos pela oficina e passaram a integrar o dossiê interno.';
COMMENT ON COLUMN nh_event_records.acceptance_dossier_sha256 IS
    'SHA-256 do dossiê do associado apresentado no momento do aceite WebAuthn. O dossiê interno/compras não participa deste hash.';
