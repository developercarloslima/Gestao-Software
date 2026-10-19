-- V75: corrige a regra comercial de danos ao veículo de terceiros e elimina
-- duplicidades criadas no catálogo para carros nacionais/importados e utilitários.
--
-- Regra oficial:
--   FIPE até R$ 50.999,99 -> cobertura de R$ 50 mil, com ou sem desconto.
--   FIPE a partir de R$ 51.000,00 -> R$ 100 mil sem desconto.
--   FIPE a partir de R$ 51.000,00 -> R$ 50 mil quando houver qualquer desconto.

-- Mantém somente as duas faixas oficiais na cobertura canônica.
DELETE FROM coverage_rules cr
USING coverages c
WHERE cr.coverage_id = c.id
  AND c.code = 'THIRD_PARTY_BASE'
  AND cr.category_code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY');

INSERT INTO coverage_rules(
    coverage_id, category_code, min_fipe, max_fipe,
    normal_amount, discounted_amount, sort_order
)
SELECT c.id, x.category_code, x.min_fipe, x.max_fipe,
       x.normal_amount, x.discounted_amount, x.sort_order
FROM coverages c
CROSS JOIN (VALUES
    ('CAR_NATIONAL', 0.00::numeric, 50999.99::numeric, 50000.00::numeric, 50000.00::numeric, 1),
    ('CAR_NATIONAL', 51000.00::numeric, NULL::numeric, 100000.00::numeric, 50000.00::numeric, 2),
    ('CAR_IMPORTED', 0.00::numeric, 50999.99::numeric, 50000.00::numeric, 50000.00::numeric, 1),
    ('CAR_IMPORTED', 51000.00::numeric, NULL::numeric, 100000.00::numeric, 50000.00::numeric, 2),
    ('UTILITY', 0.00::numeric, 50999.99::numeric, 50000.00::numeric, 50000.00::numeric, 1),
    ('UTILITY', 51000.00::numeric, NULL::numeric, 100000.00::numeric, 50000.00::numeric, 2)
) AS x(category_code, min_fipe, max_fipe, normal_amount, discounted_amount, sort_order)
WHERE c.code = 'THIRD_PARTY_BASE';

-- Atualiza a descrição no catálogo para deixar explícita a regra correta.
UPDATE plan_coverages pc
SET status = 'INCLUDED',
    monthly_price = NULL,
    detail = 'FIPE até R$ 50.999,99: R$ 50 mil. A partir de R$ 51 mil: R$ 100 mil sem desconto e R$ 50 mil com desconto.'
FROM plans p, vehicle_categories vc, coverages c
WHERE pc.plan_id = p.id
  AND p.category_id = vc.id
  AND pc.coverage_id = c.id
  AND c.code = 'THIRD_PARTY_BASE'
  AND vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY');

-- O adicional antigo não pode concorrer com a cobertura base automática.
UPDATE plan_coverages pc
SET status = 'NOT_INCLUDED',
    monthly_price = NULL,
    detail = 'Limite definido automaticamente pela FIPE e pelo desconto na cobertura de terceiros.'
FROM plans p, vehicle_categories vc, coverages c
WHERE pc.plan_id = p.id
  AND p.category_id = vc.id
  AND pc.coverage_id = c.id
  AND c.code = 'THIRD_PARTY'
  AND vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY');

-- Se foi criada manualmente outra cobertura com exatamente o mesmo nome,
-- remove sua associação dos planos afetados. O registro de cobertura somente é
-- apagado quando não está sendo usado por nenhum outro plano.
DELETE FROM plan_coverages pc
USING plans p, vehicle_categories vc, coverages c
WHERE pc.plan_id = p.id
  AND p.category_id = vc.id
  AND pc.coverage_id = c.id
  AND vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY')
  AND c.code NOT IN ('THIRD_PARTY_BASE', 'THIRD_PARTY')
  AND lower(trim(c.name)) IN ('danos ao veículo de terceiros', 'danos ao veiculo de terceiros');

DELETE FROM coverage_rules cr
USING coverages c
WHERE cr.coverage_id = c.id
  AND c.code NOT IN ('THIRD_PARTY_BASE', 'THIRD_PARTY')
  AND lower(trim(c.name)) IN ('danos ao veículo de terceiros', 'danos ao veiculo de terceiros')
  AND NOT EXISTS (SELECT 1 FROM plan_coverages pc WHERE pc.coverage_id = c.id);

-- Desmarca duplicidades históricas para que PDFs/dossiês exibam uma única linha.
UPDATE quotation_coverage_snapshots qcs
SET final_selected = FALSE
FROM quotations q
WHERE qcs.quotation_id = q.id
  AND q.category_code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY')
  AND qcs.coverage_code NOT IN ('THIRD_PARTY_BASE', 'THIRD_PARTY')
  AND lower(trim(qcs.coverage_name)) IN ('danos ao veículo de terceiros', 'danos ao veiculo de terceiros');

-- Remove também eventual seleção opcional da cobertura duplicada.
DELETE FROM quotation_optional_coverages qoc
USING quotations q
WHERE qoc.quotation_id = q.id
  AND q.category_code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY')
  AND qoc.coverage_code IN (
      SELECT c.code
      FROM coverages c
      WHERE c.code NOT IN ('THIRD_PARTY_BASE', 'THIRD_PARTY')
        AND lower(trim(c.name)) IN ('danos ao veículo de terceiros', 'danos ao veiculo de terceiros')
  );

-- Atualiza a cobertura canônica em todas as cotações existentes conforme FIPE e desconto.
UPDATE quotation_coverage_snapshots qcs
SET final_selected = TRUE,
    detail = CASE
        WHEN q.fipe_value >= 51000.00 AND COALESCE(q.discount_percent, 0) = 0
            THEN 'Cobertura de até R$ 100 mil'
        ELSE 'Cobertura de até R$ 50 mil'
    END
FROM quotations q
WHERE qcs.quotation_id = q.id
  AND q.category_code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY')
  AND qcs.coverage_code = 'THIRD_PARTY_BASE';

UPDATE quotation_coverage_snapshots qcs
SET final_selected = FALSE
FROM quotations q
WHERE qcs.quotation_id = q.id
  AND q.category_code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY')
  AND qcs.coverage_code = 'THIRD_PARTY';

DELETE FROM coverages c
WHERE c.code NOT IN ('THIRD_PARTY_BASE', 'THIRD_PARTY')
  AND lower(trim(c.name)) IN ('danos ao veículo de terceiros', 'danos ao veiculo de terceiros')
  AND NOT EXISTS (SELECT 1 FROM plan_coverages pc WHERE pc.coverage_id = c.id);
