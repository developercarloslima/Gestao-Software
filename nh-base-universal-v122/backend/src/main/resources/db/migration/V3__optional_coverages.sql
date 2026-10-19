ALTER TABLE plan_coverages
    ADD COLUMN monthly_price NUMERIC(14,2);

ALTER TABLE plan_coverages
    ADD CONSTRAINT chk_plan_coverage_monthly_price
    CHECK (monthly_price IS NULL OR monthly_price >= 0);

-- Valores mensais dos benefícios adicionais conforme o guia comercial 2026.
UPDATE plan_coverages pc
SET monthly_price = 10.00,
    detail = '30 diárias em caso de colisão; R$ 20,00 por diária; disponível 15 dias após pagar a participação'
FROM coverages c
WHERE pc.coverage_id = c.id
  AND pc.status = 'OPTIONAL'
  AND c.code = 'MOTO_REPLACEMENT';

UPDATE plan_coverages pc
SET monthly_price = 15.00
FROM coverages c
WHERE pc.coverage_id = c.id
  AND pc.status = 'OPTIONAL'
  AND c.code = 'TOW_BREAKDOWN';

UPDATE plan_coverages pc
SET monthly_price = 10.00,
    detail = 'Danos ao veículo de terceiros até R$ 10 mil'
FROM coverages c, plans p, vehicle_categories vc
WHERE pc.coverage_id = c.id
  AND pc.plan_id = p.id
  AND p.category_id = vc.id
  AND pc.status = 'OPTIONAL'
  AND c.code = 'THIRD_PARTY'
  AND vc.code LIKE 'MOTORCYCLE%';

UPDATE plan_coverages pc
SET monthly_price = 79.90,
    detail = 'Adicional de R$ 50 mil para danos ao veículo de terceiros'
FROM coverages c, plans p, vehicle_categories vc
WHERE pc.coverage_id = c.id
  AND pc.plan_id = p.id
  AND p.category_id = vc.id
  AND pc.status = 'OPTIONAL'
  AND c.code = 'THIRD_PARTY'
  AND vc.code NOT LIKE 'MOTORCYCLE%';

-- O benefício funeral possui duas modalidades comerciais distintas.
UPDATE coverages
SET name = 'Auxílio funeral individual'
WHERE code = 'FUNERAL';

UPDATE plan_coverages pc
SET monthly_price = 5.00,
    detail = 'R$ 3.000,00 para o associado'
FROM coverages c
WHERE pc.coverage_id = c.id
  AND pc.status = 'OPTIONAL'
  AND c.code = 'FUNERAL';

INSERT INTO coverages (code, name)
VALUES ('FUNERAL_FAMILY', 'Auxílio funeral familiar')
ON CONFLICT (code) DO NOTHING;

INSERT INTO plan_coverages (
    plan_id,
    coverage_id,
    status,
    detail,
    monthly_price,
    sort_order
)
SELECT
    pc.plan_id,
    family.id,
    'OPTIONAL',
    'Associado(a), esposo(a) e 1 filho; R$ 3.000,00 cada; R$ 4,00/mês por filho adicional',
    12.00,
    pc.sort_order + 1
FROM plan_coverages pc
JOIN coverages individual ON individual.id = pc.coverage_id AND individual.code = 'FUNERAL'
JOIN coverages family ON family.code = 'FUNERAL_FAMILY'
WHERE pc.status = 'OPTIONAL'
ON CONFLICT (plan_id, coverage_id) DO NOTHING;

ALTER TABLE quotations
    ADD COLUMN base_monthly_value NUMERIC(14,2);

UPDATE quotations
SET base_monthly_value = monthly_value
WHERE base_monthly_value IS NULL;

ALTER TABLE quotations
    ALTER COLUMN base_monthly_value SET NOT NULL;

CREATE TABLE quotation_optional_coverages (
    id BIGSERIAL PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    coverage_code VARCHAR(80) NOT NULL,
    coverage_name VARCHAR(180) NOT NULL,
    detail VARCHAR(240),
    monthly_price NUMERIC(14,2) NOT NULL,
    CONSTRAINT chk_quote_optional_monthly_price CHECK (monthly_price >= 0),
    CONSTRAINT uk_quotation_optional_coverage UNIQUE (quotation_id, coverage_code)
);

CREATE INDEX idx_quote_optional_quotation
    ON quotation_optional_coverages(quotation_id);
