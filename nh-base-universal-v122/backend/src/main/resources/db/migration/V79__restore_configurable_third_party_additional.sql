-- V79: separa a cobertura-base automática por FIPE do adicional comercial de terceiros.
-- THIRD_PARTY_BASE continua obrigatório/automático. THIRD_PARTY volta a ser um adicional
-- independente, editável por plano, sem conflito com as regras de desconto.

UPDATE coverages
SET name = 'Adicional de danos a terceiros + R$ 50 mil'
WHERE code = 'THIRD_PARTY';

UPDATE plan_coverages pc
SET status = 'OPTIONAL',
    monthly_price = COALESCE(pc.monthly_price, 79.90),
    detail = COALESCE(NULLIF(pc.detail, ''), 'Adicional de R$ 50 mil para danos ao veículo de terceiros')
FROM plans p, vehicle_categories vc, coverages c
WHERE pc.plan_id = p.id
  AND p.category_id = vc.id
  AND pc.coverage_id = c.id
  AND c.code = 'THIRD_PARTY'
  AND vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY');

-- Remove apenas a descrição de bloqueio criada pela migração anterior.
UPDATE plan_coverages pc
SET detail = 'Adicional de R$ 50 mil para danos ao veículo de terceiros'
FROM plans p, vehicle_categories vc, coverages c
WHERE pc.plan_id = p.id
  AND p.category_id = vc.id
  AND pc.coverage_id = c.id
  AND c.code = 'THIRD_PARTY'
  AND vc.code IN ('CAR_NATIONAL', 'CAR_IMPORTED', 'UTILITY')
  AND pc.detail = 'Limite definido automaticamente pela FIPE e pelo desconto na cobertura de terceiros.';
