-- Usuários dos portais administrados pelo próprio painel.
CREATE TABLE IF NOT EXISTS portal_users (
    id UUID PRIMARY KEY,
    username VARCHAR(160) NOT NULL,
    normalized_username VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(160),
    password_hash VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    password_changed_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ,
    created_by VARCHAR(160),
    CONSTRAINT chk_portal_user_role CHECK (role IN ('CONSULTANT', 'ANALYST', 'ADMIN'))
);

CREATE INDEX IF NOT EXISTS idx_portal_users_active_role
    ON portal_users(active, role, normalized_username);

COMMENT ON TABLE portal_users IS
    'Contas de acesso aos portais NH. Senhas são armazenadas somente como hash BCrypt.';
