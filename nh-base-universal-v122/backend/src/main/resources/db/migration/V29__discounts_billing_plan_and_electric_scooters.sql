-- Descontos comerciais, identificação do plano atual nas atualizações de boleto
-- e catálogo inicial de scooters/motos elétricas.

ALTER TABLE quotations
    ADD COLUMN pre_discount_monthly_value NUMERIC(14,2);

UPDATE quotations
SET pre_discount_monthly_value = monthly_value
WHERE pre_discount_monthly_value IS NULL;

ALTER TABLE quotations
    ALTER COLUMN pre_discount_monthly_value SET NOT NULL;

ALTER TABLE quotations
    ADD COLUMN discount_percent INTEGER NOT NULL DEFAULT 0;

ALTER TABLE quotations
    ADD COLUMN rear_window_branding VARCHAR(40) NOT NULL DEFAULT 'NOT_APPLICABLE';

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_discount_percent
    CHECK (discount_percent IN (0, 5, 10, 15, 30));

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_rear_window_branding
    CHECK (rear_window_branding IN ('NOT_APPLICABLE', 'NH_AND_OTHER_COMPANY', 'NH_ONLY'));

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_discount_branding
    CHECK (
        (discount_percent = 15 AND rear_window_branding = 'NH_AND_OTHER_COMPANY')
        OR (discount_percent = 30 AND rear_window_branding = 'NH_ONLY')
        OR (discount_percent IN (0, 5, 10) AND rear_window_branding = 'NOT_APPLICABLE')
    );

ALTER TABLE inspection_requests
    ADD COLUMN contracted_plan VARCHAR(160);

INSERT INTO vehicle_categories (code, name)
VALUES ('SCOOTER_ELECTRIC', 'Scooters e motos elétricas')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO plans (
    code, name, subtitle, category_id, region, motorcycle_origin,
    display_order, active, extra_above, extra_step, extra_increment, extra_base_price
)
VALUES (
    'SCOOTER_ELECTRIC_STANDARD',
    'Scooters e motos elétricas',
    'Coberturas específicas para scooters e motos elétricas',
    (SELECT id FROM vehicle_categories WHERE code = 'SCOOTER_ELECTRIC'),
    'NATIONAL',
    NULL,
    1,
    TRUE,
    NULL, NULL, NULL, NULL
)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    subtitle = EXCLUDED.subtitle,
    category_id = EXCLUDED.category_id,
    region = EXCLUDED.region,
    motorcycle_origin = EXCLUDED.motorcycle_origin,
    display_order = EXCLUDED.display_order,
    active = EXCLUDED.active;

INSERT INTO coverages (code, name) VALUES
    ('FUNERAL_SCOOTER', 'Auxílio funeral')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO plan_coverages (plan_id, coverage_id, status, detail, monthly_price, sort_order)
VALUES
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='ROBBERY'), 'INCLUDED', NULL, NULL, 1),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='THEFT'), 'INCLUDED', NULL, NULL, 2),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='TOW_COLLISION'), 'INCLUDED', 'Até 50 km (25 km ida e 25 km volta) em casos de colisão', NULL, 3),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='NO_CREDIT_CHECK'), 'INCLUDED', 'Sem consulta ao SPC e Serasa', NULL, 4),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='PARTICIPATION'), 'INCLUDED', '7% com mínimo de R$ 600,00', NULL, 5),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='FUNERAL_SCOOTER'), 'OPTIONAL', 'R$ 3.000,00 (individual ou família)', NULL, 6),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='SAMVIDA'), 'INCLUDED', 'Assistência médica SAMVIDA', NULL, 7),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='PHARMACY'), 'INCLUDED', 'Desconto em medicamentos de até 30% — Drogarias Sampaio', NULL, 8),
    ((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), (SELECT id FROM coverages WHERE code='CLUB'), 'INCLUDED', 'Clube de vantagens', NULL, 9)
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET
    status = EXCLUDED.status,
    detail = EXCLUDED.detail,
    monthly_price = EXCLUDED.monthly_price,
    sort_order = EXCLUDED.sort_order;

-- A imagem de cobertura não informa mensalidade/FIPE da categoria. Por segurança,
-- nenhuma faixa de preço é inventada aqui. O administrador define os valores em
-- Painel administrativo > Valores antes de gerar a primeira cotação da categoria.
