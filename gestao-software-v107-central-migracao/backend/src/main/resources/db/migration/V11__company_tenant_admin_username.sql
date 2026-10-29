-- Usuário administrador inicial de cada tenant; não é um usuário do Control Plane.
-- NULL preserva cadastros legados até a configuração explícita no painel.
ALTER TABLE platform_companies
    ADD COLUMN IF NOT EXISTS tenant_admin_username VARCHAR(120);

ALTER TABLE platform_companies
    ADD CONSTRAINT ck_company_tenant_admin_username
    CHECK (
        tenant_admin_username IS NULL OR (
            tenant_admin_username ~ '^[a-z0-9][a-z0-9._@-]{2,119}$'
            AND tenant_admin_username <> 'admin@nh.local'
        )
    );
