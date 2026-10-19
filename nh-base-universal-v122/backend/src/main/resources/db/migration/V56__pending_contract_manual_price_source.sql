-- V56: diferencia cálculo automático da tabela de ajuste manual da mensalidade.
-- Registros pendentes criados antes desta versão assumem FALSE para que uma
-- mensalidade histórica incorreta (por exemplo, com rastreador que não se
-- aplica mais) seja recalculada pela composição vigente no momento do aceite.
ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS pending_contract_manual_monthly_override BOOLEAN NOT NULL DEFAULT FALSE;
