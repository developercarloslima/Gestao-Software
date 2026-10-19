-- V83.20: nova política de passkeys do Administrador geral.
-- 1) A credencial antiga do ADMIN é invalidada uma única vez nesta atualização.
-- 2) No próximo login, após usuário + senha, o ADMIN será obrigado a cadastrar uma nova passkey.
-- 3) Depois do primeiro cadastro, o fluxo de login nunca pode sobrescrever uma passkey existente.
-- 4) Novas passkeys adicionais só podem ser cadastradas pelo próprio ADMIN já autenticado
--    na aba Usuários do painel administrativo.
CREATE TABLE IF NOT EXISTS portal_admin_passkeys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES portal_users(id) ON DELETE CASCADE,
    credential_id VARCHAR(1024) NOT NULL UNIQUE,
    public_key BYTEA NOT NULL,
    algorithm INTEGER NOT NULL,
    sign_count BIGINT NOT NULL DEFAULT 0,
    origin VARCHAR(320) NOT NULL,
    rp_id VARCHAR(253) NOT NULL,
    label VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_portal_admin_passkeys_user
    ON portal_admin_passkeys(user_id, created_at);

-- Não importa a credencial antiga: esta versão exige novo cadastro do ADMIN.
DELETE FROM portal_admin_passkeys
WHERE user_id IN (SELECT id FROM portal_users WHERE role = 'ADMIN');

UPDATE portal_users
SET passkey_credential_id = NULL,
    passkey_public_key = NULL,
    passkey_algorithm = NULL,
    passkey_sign_count = NULL,
    passkey_registration_challenge = NULL,
    passkey_registration_expires_at = NULL,
    passkey_assertion_challenge = NULL,
    passkey_assertion_expires_at = NULL,
    passkey_origin = NULL,
    passkey_rp_id = NULL,
    passkey_enrolled_at = NULL,
    updated_at = NOW()
WHERE role = 'ADMIN';
