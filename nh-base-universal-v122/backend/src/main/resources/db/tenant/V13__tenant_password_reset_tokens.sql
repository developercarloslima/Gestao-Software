-- Tokens isolados por tenant, sem guardar valor secreto em texto simples.
CREATE TABLE IF NOT EXISTS portal_password_reset_tokens (
 token_hash CHAR(64) PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES portal_users(id) ON DELETE CASCADE,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 expires_at TIMESTAMPTZ NOT NULL,
 used_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_tenant_reset_user_requested ON portal_password_reset_tokens(user_id, requested_at DESC);
