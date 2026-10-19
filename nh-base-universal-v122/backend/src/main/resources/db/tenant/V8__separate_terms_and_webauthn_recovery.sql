-- Termos independentes: sem backfill dos aceites legados de outras empresas.
ALTER TABLE inspection_responsibility_terms
 ADD COLUMN IF NOT EXISTS acceptance_source VARCHAR(32) NOT NULL DEFAULT 'STANDALONE',
 ADD COLUMN IF NOT EXISTS original_dossier_sha256 VARCHAR(64),
 ADD COLUMN IF NOT EXISTS term_content_sha256 VARCHAR(64);
-- V91 — recuperação segura de tentativas WebAuthn do Termo de Responsabilidade.
-- A resposta final recebida do autenticador é persistida em transação independente
-- antes da validação/consolidação do aceite. Se o processo cair depois do recebimento,
-- a mesma prova pode ser revalidada posteriormente sem pedir nova assinatura ao associado.

CREATE TABLE IF NOT EXISTS responsibility_term_acceptance_attempts (
    id UUID PRIMARY KEY,
    term_id UUID NOT NULL REFERENCES inspection_responsibility_terms(id) ON DELETE CASCADE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    raw_id TEXT NOT NULL,
    credential_id TEXT,
    credential_type VARCHAR(80) NOT NULL,
    client_data_json TEXT NOT NULL,
    authenticator_data TEXT NOT NULL,
    assertion_signature TEXT NOT NULL,
    user_handle TEXT,
    validation_error TEXT,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_responsibility_term_attempt_status CHECK (status IN ('RECEIVED','VERIFIED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_responsibility_term_attempt_recovery
    ON responsibility_term_acceptance_attempts(term_id, status, received_at DESC);

COMMENT ON TABLE responsibility_term_acceptance_attempts IS
    'Tentativas finais WebAuthn recebidas para o termo. Mantém a prova bruta para recuperação auditável após falha transitória do backend.';

-- V94: Termo de Ciência de Possível Prorrogação de Prazo para Peças.
-- Documento juridicamente distinto do termo de responsabilidade por avarias.
-- Mantém o histórico e o WebAuthn independentes; não migra nem converte aceites anteriores.
CREATE TABLE IF NOT EXISTS inspection_parts_delay_terms (
    LIKE inspection_responsibility_terms INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES
);
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_parts_delay_term_inspection') THEN
        ALTER TABLE inspection_parts_delay_terms ADD CONSTRAINT fk_parts_delay_term_inspection
          FOREIGN KEY (inspection_id) REFERENCES inspection_requests(id) ON DELETE CASCADE;
    END IF;
END $$;
COMMENT ON TABLE inspection_parts_delay_terms IS
    'Termo de ciência de indisponibilidade e atraso de peças, com aceite WebAuthn independente.';

CREATE TABLE IF NOT EXISTS parts_delay_term_acceptance_attempts (
    id UUID PRIMARY KEY,
    term_id UUID NOT NULL REFERENCES inspection_parts_delay_terms(id) ON DELETE CASCADE,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    raw_id TEXT NOT NULL,
    credential_id TEXT,
    credential_type VARCHAR(80) NOT NULL,
    client_data_json TEXT NOT NULL,
    authenticator_data TEXT NOT NULL,
    assertion_signature TEXT NOT NULL,
    user_handle TEXT,
    validation_error TEXT,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_parts_delay_term_attempt_status CHECK (status IN ('RECEIVED','VERIFIED','REJECTED'))
);

CREATE INDEX IF NOT EXISTS idx_parts_delay_term_attempt_recovery
    ON parts_delay_term_acceptance_attempts(term_id, status, received_at DESC);


COMMENT ON TABLE parts_delay_term_acceptance_attempts IS 'Recuperação de provas WebAuthn para o termo de ciência sobre peças.';

