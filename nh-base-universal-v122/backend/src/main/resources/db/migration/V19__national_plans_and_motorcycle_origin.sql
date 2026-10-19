-- Todos os planos e pacotes passam a possuir abrangência nacional.
-- A antiga coluna de região deixa de representar a tabela comercial das motos.
-- Para motocicletas, a seleção Capital / Demais cidades do Nordeste passa a ser
-- armazenada separadamente como origem da moto.

ALTER TABLE plans ADD COLUMN motorcycle_origin VARCHAR(20);

UPDATE plans p
SET motorcycle_origin = CASE
    WHEN p.region = 'CAPITAL' THEN 'CAPITAL'
    WHEN p.region = 'NORTHEAST' THEN 'NORTHEAST'
    ELSE 'NORTHEAST'
END
WHERE EXISTS (
    SELECT 1
    FROM vehicle_categories vc
    WHERE vc.id = p.category_id
      AND vc.code LIKE 'MOTORCYCLE%'
);

UPDATE plans
SET region = 'NATIONAL';

ALTER TABLE quotations ADD COLUMN motorcycle_origin VARCHAR(20);

UPDATE quotations
SET motorcycle_origin = CASE
    WHEN region = 'CAPITAL' THEN 'CAPITAL'
    WHEN region = 'NORTHEAST' THEN 'NORTHEAST'
    ELSE NULL
END
WHERE category_code LIKE 'MOTORCYCLE%';

UPDATE quotations
SET region = 'NATIONAL';

ALTER TABLE plans
    ADD CONSTRAINT chk_plans_national_region
    CHECK (region = 'NATIONAL');

ALTER TABLE plans
    ADD CONSTRAINT chk_plans_motorcycle_origin
    CHECK (motorcycle_origin IS NULL OR motorcycle_origin IN ('NORTHEAST', 'CAPITAL'));

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_national_region
    CHECK (region = 'NATIONAL');

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_motorcycle_origin
    CHECK (motorcycle_origin IS NULL OR motorcycle_origin IN ('NORTHEAST', 'CAPITAL'));

CREATE INDEX idx_plans_category_origin_active
    ON plans(category_id, motorcycle_origin, active, display_order);
