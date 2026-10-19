-- V90 - Persistência local da última licença/configuração confirmada pela Gestão Software.
-- O cliente continua operando com a última configuração conhecida mesmo se o Control Plane ficar indisponível.

CREATE TABLE IF NOT EXISTS gestao_tenant_config_cache (
    singleton_id SMALLINT PRIMARY KEY DEFAULT 1,
    tenant_host VARCHAR(240) NOT NULL,
    config_json TEXT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_gestao_tenant_config_singleton CHECK (singleton_id = 1)
);

COMMENT ON TABLE gestao_tenant_config_cache IS
    'Última configuração de licença/branding/módulos confirmada pelo Control Plane da Gestão Software.';
