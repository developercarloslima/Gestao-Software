-- Cooperativas são equipes internas da MESMA empresa NH: sem CNPJ ou tenant próprio.
-- O histórico temporal impede que uma transferência de consultor altere resultados antigos.
CREATE TABLE IF NOT EXISTS sales_teams (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS sales_team_memberships (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL REFERENCES sales_teams(id) ON DELETE RESTRICT,
    member_role VARCHAR(30) NOT NULL CHECK (member_role IN ('CONSULTANT','ANALYST','SUPERVISION_ANALYSIS','ADMIN')),
    -- UUIDs preservados como referência histórica, mesmo se o cadastro for apagado.
    consultant_id UUID,
    portal_user_id UUID,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    assigned_by VARCHAR(160) NOT NULL,
    CONSTRAINT ck_sales_team_member_identity CHECK (
        (consultant_id IS NOT NULL AND portal_user_id IS NULL)
        OR (consultant_id IS NULL AND portal_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_sales_team_membership_period CHECK (left_at IS NULL OR left_at >= joined_at)
);

-- Um CONSULTOR ativo não pode integrar duas equipes simultaneamente.
-- Analistas, supervisores e administradores podem apoiar mais de uma equipe.
CREATE UNIQUE INDEX uq_sales_teams_active_consultant
    ON sales_team_memberships(consultant_id)
    WHERE consultant_id IS NOT NULL AND member_role = 'CONSULTANT' AND left_at IS NULL;
CREATE UNIQUE INDEX uq_sales_teams_active_person_in_team
    ON sales_team_memberships(team_id, consultant_id)
    WHERE consultant_id IS NOT NULL AND left_at IS NULL;
CREATE UNIQUE INDEX uq_sales_teams_active_admin_in_team
    ON sales_team_memberships(team_id, portal_user_id)
    WHERE portal_user_id IS NOT NULL AND left_at IS NULL;
CREATE INDEX idx_sales_team_membership_period
    ON sales_team_memberships(consultant_id, joined_at, left_at);
CREATE INDEX idx_sales_team_membership_team
    ON sales_team_memberships(team_id, left_at);
CREATE INDEX idx_sales_team_signed_inspections
    ON inspection_requests(accepted_at, consultant_id)
    WHERE accepted_at IS NOT NULL;
CREATE UNIQUE INDEX uq_sales_team_name_active
    ON sales_teams(normalized_name) WHERE deleted_at IS NULL;

COMMENT ON TABLE sales_teams IS
    'Cooperativas internas de vendas; todas pertencem à mesma empresa. Exclusão lógica preserva histórico.';
COMMENT ON TABLE sales_team_memberships IS
    'Vínculos temporais que preservam a equipe responsável por vendas na data de assinatura.';
