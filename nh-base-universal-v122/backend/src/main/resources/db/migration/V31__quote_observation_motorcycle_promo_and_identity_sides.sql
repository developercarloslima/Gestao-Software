-- Observação comercial e cilindrada usada na tabela promocional de motocicletas.
ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS observation VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS motorcycle_cc INTEGER;

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS chk_quotations_motorcycle_cc;

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_motorcycle_cc
    CHECK (motorcycle_cc IS NULL OR motorcycle_cc BETWEEN 1 AND 2500);

-- A tabela promocional já existe no catálogo. A ativação/desativação continua
-- sendo controlada exclusivamente pela aba Planos do painel administrativo.
UPDATE plans
SET region = 'NATIONAL', motorcycle_origin = NULL
WHERE code = 'MOTO_PROMO_2026';

COMMENT ON COLUMN quotations.observation IS
    'Observação comercial informada no momento da cotação.';
COMMENT ON COLUMN quotations.motorcycle_cc IS
    'Cilindrada informada para motocicletas convencionais; usada para elegibilidade da tabela promocional.';
