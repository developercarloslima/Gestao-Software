-- Registra o último acesso de cada consultor ao portal e permite distribuir
-- automaticamente as cotações públicas ao consultor mais recentemente ativo.
ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS last_portal_login_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_consultants_last_portal_login
    ON consultants(active, last_portal_login_at DESC)
    WHERE last_portal_login_at IS NOT NULL;
