-- V54: revisão comercial final, validade de vistoria e benefícios por desconto/FIPE.
-- Regra de terceiros para automóveis nacionais/importados e utilitários:
--   FIPE até 50.999,99 => R$ 50 mil
--   FIPE a partir de 51.000,00 => R$ 100 mil
-- O desconto não reduz o limite de terceiros.

ALTER TABLE quotation_coverage_snapshots
    ADD COLUMN IF NOT EXISTS final_selected BOOLEAN;

UPDATE quotation_coverage_snapshots qcs
SET final_selected = (
    qcs.coverage_status = 'INCLUDED'
    OR EXISTS (
        SELECT 1
        FROM quotation_optional_coverages qoc
        WHERE qoc.quotation_id = qcs.quotation_id
          AND UPPER(qoc.coverage_code) = UPPER(qcs.coverage_code)
    )
)
WHERE qcs.final_selected IS NULL;

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS pending_contract_discount_percent INTEGER,
    ADD COLUMN IF NOT EXISTS pending_contract_rear_window_branding VARCHAR(40),
    ADD COLUMN IF NOT EXISTS pending_contract_benefit_codes VARCHAR(4000);

INSERT INTO coverages (code, name) VALUES
    ('NATURAL_PHENOMENA', 'Fenômenos da natureza'),
    ('SMALL_REPAIRS', 'Pequenos reparos')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

-- Benefícios do plano completo. Quando houver qualquer desconto, a aplicação os
-- retira automaticamente do dossiê final.
INSERT INTO plan_coverages (plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'INCLUDED', d.detail, NULL, d.sort_order
FROM (VALUES
    ('CAR_COMPLETO', 'NATURAL_PHENOMENA', 'Cobertura conforme regulamento do plano', 23),
    ('CAR_COMPLETO', 'SMALL_REPAIRS', 'Pequenos reparos conforme regulamento do plano', 24),
    ('UTILITY_COMPLETO', 'NATURAL_PHENOMENA', 'Cobertura conforme regulamento do plano', 23),
    ('UTILITY_COMPLETO', 'SMALL_REPAIRS', 'Pequenos reparos conforme regulamento do plano', 24),
    ('CAR_IMPORTED', 'NATURAL_PHENOMENA', 'Cobertura conforme regulamento do plano', 19),
    ('CAR_IMPORTED', 'SMALL_REPAIRS', 'Pequenos reparos conforme regulamento do plano', 20)
) AS d(plan_code, coverage_code, detail, sort_order)
JOIN plans p ON p.code = d.plan_code
JOIN coverages c ON c.code = d.coverage_code
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET
    status = EXCLUDED.status,
    detail = EXCLUDED.detail,
    monthly_price = EXCLUDED.monthly_price,
    sort_order = EXCLUDED.sort_order;

-- O adicional antigo de terceiros deixa de ser ofertado para estas categorias.
UPDATE plan_coverages pc
SET status = 'NOT_INCLUDED',
    monthly_price = NULL,
    detail = 'Limite definido automaticamente pela FIPE: R$ 50 mil até 50.999,99 e R$ 100 mil a partir de 51 mil'
FROM plans p, coverages c, vehicle_categories vc
WHERE pc.plan_id = p.id
  AND pc.coverage_id = c.id
  AND p.category_id = vc.id
  AND c.code = 'THIRD_PARTY'
  AND vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY');

UPDATE plan_coverages pc
SET detail = 'Limite definido automaticamente pela FIPE: R$ 50 mil até 50.999,99 e R$ 100 mil a partir de 51 mil'
FROM plans p, coverages c, vehicle_categories vc
WHERE pc.plan_id = p.id
  AND pc.coverage_id = c.id
  AND p.category_id = vc.id
  AND c.code = 'THIRD_PARTY_BASE'
  AND vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY');

-- Regras explícitas no catálogo administrativo; discounted_amount é igual ao
-- normal_amount para garantir que desconto nunca reduza terceiros.
DELETE FROM coverage_rules cr
USING coverages c
WHERE cr.coverage_id = c.id
  AND c.code = 'THIRD_PARTY_BASE'
  AND cr.category_code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY');

INSERT INTO coverage_rules(coverage_id, category_code, min_fipe, max_fipe, normal_amount, discounted_amount, sort_order)
SELECT c.id, x.category_code, x.min_fipe, x.max_fipe, x.amount, x.amount, x.sort_order
FROM coverages c
CROSS JOIN (VALUES
    ('CAR_NATIONAL', 0.00::numeric, 50999.99::numeric, 50000.00::numeric, 1),
    ('CAR_NATIONAL', 51000.00::numeric, NULL::numeric, 100000.00::numeric, 2),
    ('CAR_IMPORTED', 0.00::numeric, 50999.99::numeric, 50000.00::numeric, 1),
    ('CAR_IMPORTED', 51000.00::numeric, NULL::numeric, 100000.00::numeric, 2),
    ('UTILITY', 0.00::numeric, 50999.99::numeric, 50000.00::numeric, 1),
    ('UTILITY', 51000.00::numeric, NULL::numeric, 100000.00::numeric, 2)
) AS x(category_code, min_fipe, max_fipe, amount, sort_order)
WHERE c.code = 'THIRD_PARTY_BASE';

-- Atualiza os snapshots históricos para que o dossiê final reflita a regra
-- vigente, sem apagar os registros financeiros históricos da cotação.
UPDATE quotation_coverage_snapshots qcs
SET final_selected = TRUE,
    detail = CASE WHEN q.fipe_value >= 51000.00
                  THEN 'Cobertura de até R$ 100 mil'
                  ELSE 'Cobertura de até R$ 50 mil' END
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

-- Acrescenta os novos benefícios aos snapshots das cotações dos planos completos.
INSERT INTO quotation_coverage_snapshots(
    quotation_id, coverage_code, coverage_name, coverage_status, detail,
    monthly_price, sort_order, final_selected
)
SELECT q.id, c.code, c.name, pc.status, pc.detail, pc.monthly_price, pc.sort_order,
       CASE WHEN COALESCE(q.discount_percent, 0) > 0 THEN FALSE ELSE TRUE END
FROM quotations q
JOIN plans p ON p.code = q.selected_plan_code
JOIN plan_coverages pc ON pc.plan_id = p.id
JOIN coverages c ON c.id = pc.coverage_id
WHERE c.code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS')
ON CONFLICT (quotation_id, coverage_code) DO UPDATE SET
    coverage_name = EXCLUDED.coverage_name,
    coverage_status = EXCLUDED.coverage_status,
    detail = EXCLUDED.detail,
    monthly_price = EXCLUDED.monthly_price,
    sort_order = EXCLUDED.sort_order,
    final_selected = EXCLUDED.final_selected;

UPDATE quotation_coverage_snapshots qcs
SET final_selected = FALSE
FROM quotations q
WHERE qcs.quotation_id = q.id
  AND COALESCE(q.discount_percent, 0) > 0
  AND qcs.coverage_code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS');

-- A existência de arquivo impede o vencimento comercial da vistoria, mas não altera
-- a retenção operacional de 40 dias. A normalização física dos arquivos é aplicada
-- pela migração V55 para também corrigir relatórios legados com expires_at NULL.
