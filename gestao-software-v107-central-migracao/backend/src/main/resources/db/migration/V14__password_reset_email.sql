-- E-mails de recuperação de administradores da Gestão e tokens de uso único.
ALTER TABLE gs_users ADD COLUMN IF NOT EXISTS recovery_email VARCHAR(180);
UPDATE gs_users SET recovery_email='developercarloslima@gmail.com' WHERE username='carlos.lima';
UPDATE gs_users SET recovery_email='pedro_tubista@hotmail.com' WHERE username='pedro.henrique';
CREATE TABLE IF NOT EXISTS gs_password_reset_tokens (
 token_hash CHAR(64) PRIMARY KEY,
 username VARCHAR(120) NOT NULL REFERENCES gs_users(username) ON DELETE CASCADE,
 requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 expires_at TIMESTAMPTZ NOT NULL,
 used_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_gs_reset_username_requested ON gs_password_reset_tokens(username, requested_at DESC);
