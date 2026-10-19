-- V84 — aceite independente do Termo de Responsabilidade por avaria pré-existente.
--
-- O termo deixa de depender do aceite geral do dossiê. O ADM pode preparar e
-- enviar somente o termo mesmo depois da vistoria concluída/aprovada e mesmo se
-- o dossiê principal já tiver sido aceito digitalmente.

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
