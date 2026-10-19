-- V81 — catálogo único de benefícios/adicionais por tipo de veículo.
--
-- Objetivos:
-- 1) manter uma única fonte de verdade em plan_coverages para dizer se um benefício
--    é integrado (INCLUDED), adicional pago (OPTIONAL) ou não ofertado;
-- 2) registrar em quais categorias cada benefício pode ser vendido como adicional;
-- 3) separar os adicionais de terceiros por valor para não reutilizar o mesmo código
--    com nomes/preços incompatíveis entre motos e automóveis;
-- 4) normalizar os adicionais informados comercialmente;
-- 5) manter Fenômenos da Natureza + Pequenos Reparos até R$ 1.000,00 somente nos
--    planos completos de carro/utilitário. A retirada com desconto continua sendo
--    garantida pelo backend e pela migração V80.

ALTER TABLE coverages
    ADD COLUMN IF NOT EXISTS optional_vehicle_categories VARCHAR(1000) NOT NULL DEFAULT '';

-- Preserva a elegibilidade dos opcionais já existentes antes da normalização.
UPDATE coverages c
SET optional_vehicle_categories = x.codes
FROM (
    SELECT pc.coverage_id,
           string_agg(DISTINCT vc.code, ',' ORDER BY vc.code) AS codes
    FROM plan_coverages pc
    JOIN plans p ON p.id = pc.plan_id
    JOIN vehicle_categories vc ON vc.id = p.category_id
    WHERE pc.status = 'OPTIONAL'
    GROUP BY pc.coverage_id
) x
WHERE c.id = x.coverage_id
  AND COALESCE(c.optional_vehicle_categories, '') = '';

-- Códigos novos e inequívocos para os adicionais de terceiros.
INSERT INTO coverages(code, name, optional_vehicle_categories) VALUES
    ('THIRD_PARTY_ADD_7K',  'Adicional de R$ 7 mil para danos a terceiros',  'MOTORCYCLE_OVER_300,MOTORCYCLE_PROMO_2026,MOTORCYCLE_UP_TO_300'),
    ('THIRD_PARTY_ADD_10K', 'Adicional de R$ 10 mil para danos a terceiros', 'MOTORCYCLE_OVER_300,MOTORCYCLE_PROMO_2026,MOTORCYCLE_UP_TO_300'),
    ('THIRD_PARTY_ADD_50K', 'Adicional de R$ 50 mil para danos a terceiros', 'CAR_IMPORTED,CAR_NATIONAL,TRUCK,UTILITY')
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    optional_vehicle_categories = EXCLUDED.optional_vehicle_categories;

-- O benefício-base THIRD_PARTY_BASE permanece intacto. Apenas o adicional legado
-- THIRD_PARTY é retirado do catálogo atual para não aparecer duplicado.
DELETE FROM plan_coverages pc
USING coverages c
WHERE pc.coverage_id = c.id AND c.code = 'THIRD_PARTY';

DELETE FROM discount_rule_benefits drb
USING coverages c
WHERE drb.coverage_id = c.id AND c.code = 'THIRD_PARTY';

DELETE FROM coverage_rules cr
USING coverages c
WHERE cr.coverage_id = c.id AND c.code = 'THIRD_PARTY';

DELETE FROM coverages WHERE code = 'THIRD_PARTY';

-- O opcional antigo de moto reserva não faz parte da relação comercial atual.
DELETE FROM plan_coverages pc
USING coverages c
WHERE pc.coverage_id = c.id
  AND c.code = 'MOTO_REPLACEMENT'
  AND pc.status = 'OPTIONAL';

UPDATE coverages
SET optional_vehicle_categories = ''
WHERE code = 'MOTO_REPLACEMENT';

-- Remove o funeral genérico antigo de scooter e passa a usar os mesmos dois
-- benefícios oficiais (individual e familiar), evitando duplicidade no Admin.
DELETE FROM plan_coverages pc
USING coverages c
WHERE pc.coverage_id = c.id AND c.code = 'FUNERAL_SCOOTER';
DELETE FROM discount_rule_benefits drb
USING coverages c
WHERE drb.coverage_id = c.id AND c.code = 'FUNERAL_SCOOTER';
DELETE FROM coverage_rules cr
USING coverages c
WHERE cr.coverage_id = c.id AND c.code = 'FUNERAL_SCOOTER';
DELETE FROM coverages WHERE code = 'FUNERAL_SCOOTER';

-- Funeral individual e familiar são adicionais permitidos para todos os tipos
-- de veículo atualmente cadastrados.
UPDATE coverages
SET optional_vehicle_categories = (
    SELECT string_agg(vc.code, ',' ORDER BY vc.code) FROM vehicle_categories vc
)
WHERE code IN ('FUNERAL', 'FUNERAL_FAMILY');

-- Reboque adicional da relação comercial: somente motos.
UPDATE coverages
SET optional_vehicle_categories = 'MOTORCYCLE_OVER_300,MOTORCYCLE_PROMO_2026,MOTORCYCLE_UP_TO_300'
WHERE code = 'TOW_BREAKDOWN';

-- Benefícios integrados não são ofertados como adicionais.
UPDATE coverages
SET optional_vehicle_categories = ''
WHERE code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS');

-- Funeral individual R$ 5 e familiar até 3 pessoas R$ 12 em todos os planos.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'OPTIONAL', x.detail, x.price,
       COALESCE((SELECT MAX(pc2.sort_order) FROM plan_coverages pc2 WHERE pc2.plan_id = p.id), 0) + x.offset_order
FROM plans p
CROSS JOIN (VALUES
    ('FUNERAL', 'Auxílio funeral individual', 5.00::numeric, 10),
    ('FUNERAL_FAMILY', 'Auxílio funeral familiar até 3 pessoas', 12.00::numeric, 20)
) AS x(code, detail, price, offset_order)
JOIN coverages c ON c.code = x.code
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET
    status = EXCLUDED.status,
    detail = EXCLUDED.detail,
    monthly_price = EXCLUDED.monthly_price;

-- Motos: +R$ 7 mil (R$ 5), +R$ 10 mil (R$ 10) e reboque 300 km (R$ 15).
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'OPTIONAL', x.detail, x.price, x.sort_order
FROM plans p
JOIN vehicle_categories vc ON vc.id = p.category_id
CROSS JOIN (VALUES
    ('THIRD_PARTY_ADD_7K',  'Adicional de R$ 7 mil para danos ao veículo de terceiros', 5.00::numeric, 70),
    ('THIRD_PARTY_ADD_10K', 'Adicional de R$ 10 mil para danos ao veículo de terceiros', 10.00::numeric, 71),
    ('TOW_BREAKDOWN', '6 acionamentos por ano; raio total de 300 km (150 km ida + 150 km volta)', 15.00::numeric, 72)
) AS x(code, detail, price, sort_order)
JOIN coverages c ON c.code = x.code
WHERE vc.code IN ('MOTORCYCLE_UP_TO_300', 'MOTORCYCLE_OVER_300', 'MOTORCYCLE_PROMO_2026')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET
    status = EXCLUDED.status,
    detail = EXCLUDED.detail,
    monthly_price = EXCLUDED.monthly_price,
    sort_order = EXCLUDED.sort_order;

-- Carros nacionais/importados, utilitários e demais veículos (caminhões):
-- adicional de R$ 50 mil para terceiros por R$ 29,90.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'OPTIONAL', 'Adicional de R$ 50 mil para danos ao veículo de terceiros', 29.90, 70
FROM plans p
JOIN vehicle_categories vc ON vc.id = p.category_id
JOIN coverages c ON c.code = 'THIRD_PARTY_ADD_50K'
WHERE vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY', 'TRUCK')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET
    status = EXCLUDED.status,
    detail = EXCLUDED.detail,
    monthly_price = EXCLUDED.monthly_price,
    sort_order = EXCLUDED.sort_order;

-- Fenômenos da Natureza e Pequenos Reparos ficam estritamente nos dois planos
-- completos solicitados e sempre como benefício integrado ao plano.
DELETE FROM plan_coverages pc
USING coverages c, plans p
WHERE pc.coverage_id = c.id
  AND pc.plan_id = p.id
  AND c.code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS')
  AND p.code NOT IN ('CAR_COMPLETO', 'UTILITY_COMPLETO');

INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'INCLUDED', x.detail, NULL, x.sort_order
FROM (VALUES
    ('CAR_COMPLETO', 'NATURAL_PHENOMENA', 'Fenômenos da natureza conforme regulamento do plano', 80),
    ('CAR_COMPLETO', 'SMALL_REPAIRS', 'Pequenos reparos até R$ 1.000,00', 81),
    ('UTILITY_COMPLETO', 'NATURAL_PHENOMENA', 'Fenômenos da natureza conforme regulamento do plano', 80),
    ('UTILITY_COMPLETO', 'SMALL_REPAIRS', 'Pequenos reparos até R$ 1.000,00', 81)
) AS x(plan_code, coverage_code, detail, sort_order)
JOIN plans p ON p.code = x.plan_code
JOIN coverages c ON c.code = x.coverage_code
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET
    status = 'INCLUDED',
    detail = EXCLUDED.detail,
    monthly_price = NULL,
    sort_order = EXCLUDED.sort_order;

-- Mantém snapshots históricos, mas garante que qualquer cotação com desconto
-- continue sem os dois benefícios integrados especiais.
UPDATE quotation_coverage_snapshots qcs
SET final_selected = FALSE
FROM quotations q
WHERE qcs.quotation_id = q.id
  AND COALESCE(q.discount_percent, 0) > 0
  AND qcs.coverage_code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS');
