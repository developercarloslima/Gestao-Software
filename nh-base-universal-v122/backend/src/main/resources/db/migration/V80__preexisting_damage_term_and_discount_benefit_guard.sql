-- V80 — avarias pré-existentes no Retrato NH + trava estrutural dos benefícios com desconto.
--
-- 1) O Retrato NH passa a registrar, quando informado no cadastro, as avarias
--    pré-existentes que serão incorporadas ao termo de responsabilidade do dossiê.
-- 2) Fenômenos da natureza e Pequenos reparos pertencem somente aos planos completos
--    e nunca podem permanecer selecionados quando houver qualquer desconto.

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS preexisting_damage BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS preexisting_damage_details TEXT;

-- Mantém estes dois benefícios apenas nos planos completos de automóveis/utilitários.
DELETE FROM plan_coverages pc
USING coverages c, plans p
WHERE pc.coverage_id = c.id
  AND pc.plan_id = p.id
  AND c.code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS')
  AND p.code NOT IN ('CAR_COMPLETO', 'UTILITY_COMPLETO');

-- Corrige snapshots já existentes: qualquer cotação com desconto não leva estes benefícios.
UPDATE quotation_coverage_snapshots qcs
SET final_selected = FALSE
FROM quotations q
WHERE qcs.quotation_id = q.id
  AND COALESCE(q.discount_percent, 0) > 0
  AND qcs.coverage_code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS');
