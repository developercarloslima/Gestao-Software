-- V82 — catálogo oficial de coberturas + regras editáveis pelo ADM.
--
-- Princípios:
-- 1) plan_coverages continua sendo a única fonte de verdade para vínculo com plano,
--    status integrado/opcional e valor do adicional;
-- 2) discount_rule_benefits continua sendo a única fonte para efeito por percentual
--    de desconto (agora também aceita KEEP para alterar somente limite/texto);
-- 3) condição do evento/dano e raio de reboque pertencem ao vínculo plano/cobertura,
--    porque podem variar entre Econômico, Completo, caminhões e motos;
-- 4) THIRD_PARTY_BASE permanece com a regra fixa do backend: FIPE < 51 mil => 50 mil;
--    FIPE >= 51 mil sem desconto => 100 mil; com desconto => 50 mil.

ALTER TABLE plan_coverages
    ADD COLUMN IF NOT EXISTS damage_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS event_condition VARCHAR(32),
    ADD COLUMN IF NOT EXISTS towing_radius_km INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_plan_coverage_damage_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_damage_condition
            CHECK (damage_condition IS NULL OR damage_condition IN ('ANY_DAMAGE','TOTAL_ONLY','PARTIAL_ONLY'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_plan_coverage_event_condition') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_event_condition
            CHECK (event_condition IS NULL OR event_condition IN ('ANY_ORIGIN','COLLISION_DERIVED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_plan_coverage_towing_radius') THEN
        ALTER TABLE plan_coverages ADD CONSTRAINT chk_plan_coverage_towing_radius
            CHECK (towing_radius_km IS NULL OR towing_radius_km >= 0);
    END IF;
END $$;

ALTER TABLE discount_rule_benefits DROP CONSTRAINT IF EXISTS chk_discount_rule_benefit_action;
ALTER TABLE discount_rule_benefits ADD CONSTRAINT chk_discount_rule_benefit_action
    CHECK (action IN ('KEEP','REMOVE','FORCE_INCLUDE'));


-- A cobertura-base de terceiros possui regra comercial fixa por FIPE/desconto.
-- Remove qualquer override administrativo legado que poderia concorrer com ela.
DELETE FROM discount_rule_benefits
WHERE coverage_id IN (SELECT id FROM coverages WHERE UPPER(code) = 'THIRD_PARTY_BASE');

-- ---------------------------------------------------------------------------
-- Catálogo sem duplicidade: roubo + furto vira uma cobertura única; colisão,
-- queda e capotamento passam a ser independentes.
-- ---------------------------------------------------------------------------
UPDATE coverages SET name = 'Roubo e furto' WHERE code = 'ROBBERY';
UPDATE coverages SET name = 'Colisão' WHERE code = 'COLLISION';
INSERT INTO coverages(code, name, optional_vehicle_categories) VALUES
    ('FALL', 'Queda', ''),
    ('ROLLOVER', 'Capotamento', '')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

-- THEFT era a segunda linha da mesma proteção. Snapshots históricos guardam
-- código/nome em texto e permanecem íntegros; somente novas cotações usam ROBBERY.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='THEFT';
DELETE FROM coverage_rules cr USING coverages c WHERE cr.coverage_id=c.id AND c.code='THEFT';
DELETE FROM discount_rule_benefits drb USING coverages c WHERE drb.coverage_id=c.id AND c.code='THEFT';
DELETE FROM coverages WHERE code='THEFT';

UPDATE coverages SET name='Veículo reserva — 10 diárias' WHERE code='VEHICLE_REPLACEMENT';
UPDATE coverages SET name='Moto reserva — 30 diárias' WHERE code='MOTO_REPLACEMENT';
UPDATE coverages SET name='Cobertura de vidros — 50%' WHERE code='GLASS';
UPDATE coverages SET name='Auxílio-funeral individual' WHERE code='FUNERAL';
UPDATE coverages SET name='Auxílio-funeral familiar' WHERE code='FUNERAL_FAMILY';
UPDATE coverages SET name='Descontos em medicamentos — Drogarias Sampaio' WHERE code='PHARMACY';
UPDATE coverages SET name='Reboque em caso de colisão' WHERE code='TOW_COLLISION';
UPDATE coverages SET name='Reboque por pane mecânica ou elétrica' WHERE code='TOW_BREAKDOWN';

-- Helper pattern: para as coberturas abaixo, reconstruímos APENAS os vínculos
-- oficiais listados pelo cliente. Demais benefícios (rastreador, participação,
-- fenômenos, pequenos reparos e adicionais) ficam intactos.

-- Roubo e furto: todos os planos atualmente cadastrados.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='ROBBERY';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'INCLUDED', NULL, NULL, 10
FROM plans p JOIN coverages c ON c.code='ROBBERY'
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=NULL, monthly_price=NULL, sort_order=10;

-- Colisão.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='COLLISION';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'INCLUDED', NULL, NULL, 20
FROM plans p JOIN coverages c ON c.code='COLLISION'
WHERE p.code IN (
    'MOTO_COMPLETO_NE','MOTO_COMPLETO_CAPITAL','MOTO_PROMO_2026',
    'CAR_ECONOMICO','CAR_COMPLETO','UTILITY_ECONOMICO','UTILITY_COMPLETO',
    'TRUCK_OURO_SDT','TRUCK_OURO_PLUS','TRUCK_DIAMANTE_PLUS'
)
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=NULL, monthly_price=NULL, sort_order=20;

-- Queda: somente motos Completo/Capitais/Promocional.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='FALL';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'INCLUDED', NULL, NULL, 30
FROM plans p JOIN coverages c ON c.code='FALL'
WHERE p.code IN ('MOTO_COMPLETO_NE','MOTO_COMPLETO_CAPITAL','MOTO_PROMO_2026')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=NULL, monthly_price=NULL, sort_order=30;

-- Capotamento: carros nacionais Econômico/Completo, utilitários e caminhões.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='ROLLOVER';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id, c.id, 'INCLUDED', NULL, NULL, 40
FROM plans p JOIN coverages c ON c.code='ROLLOVER'
WHERE p.code IN ('CAR_ECONOMICO','CAR_COMPLETO','UTILITY_ECONOMICO','UTILITY_COMPLETO','TRUCK_OURO_SDT','TRUCK_OURO_PLUS','TRUCK_DIAMANTE_PLUS')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=NULL, monthly_price=NULL, sort_order=40;

-- Incêndio. Econômico e Ouro SDT: somente derivado de colisão.
-- Capitais/Promocional, planos Completos e caminhões superiores: incêndio de qualquer origem, dano total ou parcial.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='FIRE';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, damage_condition, event_condition)
SELECT p.id, c.id, 'INCLUDED', NULL, NULL, 50, 'ANY_DAMAGE',
       CASE WHEN p.code IN ('CAR_ECONOMICO','UTILITY_ECONOMICO','TRUCK_OURO_SDT')
            THEN 'COLLISION_DERIVED' ELSE 'ANY_ORIGIN' END
FROM plans p JOIN coverages c ON c.code='FIRE'
WHERE p.code IN (
    'MOTO_COMPLETO_CAPITAL','MOTO_PROMO_2026',
    'CAR_ECONOMICO','CAR_COMPLETO','UTILITY_ECONOMICO','UTILITY_COMPLETO',
    'TRUCK_OURO_SDT','TRUCK_OURO_PLUS','TRUCK_DIAMANTE_PLUS'
)
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=NULL, monthly_price=NULL, sort_order=50,
    damage_condition=EXCLUDED.damage_condition, event_condition=EXCLUDED.event_condition;

-- Perda total.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='TOTAL_LOSS';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, damage_condition)
SELECT p.id, c.id, 'INCLUDED', NULL, NULL, 60, 'TOTAL_ONLY'
FROM plans p JOIN coverages c ON c.code='TOTAL_LOSS'
WHERE p.code IN ('MOTO_COMPLETO_NE','MOTO_COMPLETO_CAPITAL','MOTO_PROMO_2026','TRUCK_OURO_SDT','TRUCK_OURO_PLUS','TRUCK_DIAMANTE_PLUS')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=NULL, monthly_price=NULL, sort_order=60, damage_condition='TOTAL_ONLY';

-- Danos ao veículo de terceiros. Mantém THIRD_PARTY_BASE como cobertura canônica.
-- O limite de carros/importados/utilitários continua protegido pela regra FIPE/desconto.
-- Os opcionais cuja mensalidade não foi informada pelo cliente ficam sem preço: a API
-- impede a contratação até que o ADM defina o valor, em vez de inventarmos um preço.
UPDATE coverages
SET optional_vehicle_categories='CAR_NATIONAL,MOTORCYCLE_OVER_300,MOTORCYCLE_UP_TO_300,TRUCK'
WHERE code='THIRD_PARTY_BASE';

INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED',x.detail,NULL,65
FROM (VALUES
    ('MOTO_COMPLETO_NE','Cobertura de até R$ 10 mil'),
    ('MOTO_COMPLETO_CAPITAL','Cobertura de até R$ 10 mil'),
    ('CAR_ECONOMICO','FIPE até R$ 50.999,99: R$ 50 mil. A partir de R$ 51 mil: R$ 100 mil sem desconto e R$ 50 mil com desconto.'),
    ('CAR_COMPLETO','FIPE até R$ 50.999,99: R$ 50 mil. A partir de R$ 51 mil: R$ 100 mil sem desconto e R$ 50 mil com desconto.'),
    ('UTILITY_ECONOMICO','FIPE até R$ 50.999,99: R$ 50 mil. A partir de R$ 51 mil: R$ 100 mil sem desconto e R$ 50 mil com desconto.'),
    ('UTILITY_COMPLETO','FIPE até R$ 50.999,99: R$ 50 mil. A partir de R$ 51 mil: R$ 100 mil sem desconto e R$ 50 mil com desconto.'),
    ('TRUCK_OURO_PLUS','Cobertura de R$ 50 mil para danos ao veículo de terceiros'),
    ('TRUCK_DIAMANTE_PLUS','Cobertura de até R$ 100 mil para danos ao veículo de terceiros')
) AS x(plan_code,detail)
JOIN plans p ON p.code=x.plan_code
JOIN coverages c ON c.code='THIRD_PARTY_BASE'
ON CONFLICT (plan_id, coverage_id) DO UPDATE
SET status='INCLUDED', detail=EXCLUDED.detail, monthly_price=NULL, sort_order=65;

INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'OPTIONAL',x.detail,NULL,65
FROM (VALUES
    ('MOTO_RF_NE','Cobertura opcional de até R$ 10 mil; definir valor mensal no Admin'),
    ('MOTO_RF_CAPITAL','Cobertura opcional de até R$ 10 mil; definir valor mensal no Admin'),
    ('MOTO_RF_OVER_NE','Cobertura opcional de até R$ 10 mil; definir valor mensal no Admin'),
    ('MOTO_RF_OVER_CAPITAL','Cobertura opcional de até R$ 10 mil; definir valor mensal no Admin'),
    ('TRUCK_OURO_SDT','Cobertura opcional de terceiros; definir limite e valor mensal no Admin')
) AS x(plan_code,detail)
JOIN plans p ON p.code=x.plan_code
JOIN coverages c ON c.code='THIRD_PARTY_BASE'
ON CONFLICT (plan_id, coverage_id) DO UPDATE
SET status='OPTIONAL', detail=EXCLUDED.detail, monthly_price=NULL, sort_order=65;

-- Carro Nacional R/F pode ser criado pelo Admin e não possui código fixo no seed.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'OPTIONAL',
       'Regra FIPE: até R$ 50.999,99 = R$ 50 mil; a partir de R$ 51 mil = R$ 100 mil sem desconto e R$ 50 mil com desconto. Definir valor mensal no Admin.',
       NULL,65
FROM plans p
JOIN vehicle_categories vc ON vc.id=p.category_id AND vc.code='CAR_NATIONAL'
JOIN coverages c ON c.code='THIRD_PARTY_BASE'
WHERE lower(p.name) LIKE '%roubo%' AND lower(p.name) LIKE '%furto%'
ON CONFLICT (plan_id, coverage_id) DO UPDATE
SET status='OPTIONAL', detail=EXCLUDED.detail, monthly_price=NULL, sort_order=65;

-- Carros importados constam no guia, mas o material atual pede confirmação da
-- condição de contratação. Por isso o vínculo existente é preservado sem
-- alterar status/preço automaticamente nesta migração.

-- Reboque em caso de colisão: limpa somente esse serviço e reinsere os planos oficiais.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='TOW_COLLISION';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, towing_radius_km)
SELECT p.id, c.id, 'INCLUDED', x.detail, NULL, 70, x.km
FROM (VALUES
    ('MOTO_COMPLETO_NE',1000,'Conforme regulamento do plano'),
    ('MOTO_COMPLETO_CAPITAL',1000,'Conforme regulamento do plano'),
    ('MOTO_PROMO_2026',1000,'Conforme regulamento do plano'),
    ('MOTO_RF_NE',1000,'Conforme regulamento do plano'),
    ('MOTO_RF_CAPITAL',1000,'Conforme regulamento do plano'),
    ('MOTO_RF_OVER_NE',1000,'Conforme regulamento do plano'),
    ('MOTO_RF_OVER_CAPITAL',1000,'Conforme regulamento do plano'),
    ('MOTO_SCOOTER_ELECTRIC',50,'Conforme regulamento do plano'),
    ('CAR_ECONOMICO',500,'Conforme regulamento do plano'),
    ('CAR_COMPLETO',1000,'Conforme regulamento do plano'),
    ('CAR_IMPORTED',1000,'Conforme regulamento do plano')
) AS x(plan_code,km,detail)
JOIN plans p ON p.code=x.plan_code
JOIN coverages c ON c.code='TOW_COLLISION'
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=EXCLUDED.detail, monthly_price=NULL, sort_order=70, towing_radius_km=EXCLUDED.towing_radius_km;

-- Utilitários já possuíam quilometragem livre no catálogo. O áudio atual só
-- altera expressamente carros nacionais: Econômico 500 km e Completo 1.000 km.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, towing_radius_km)
SELECT p.id, c.id, 'INCLUDED', 'Quilometragem livre em caso de colisão', NULL, 70, NULL
FROM plans p JOIN coverages c ON c.code='TOW_COLLISION'
WHERE p.code IN ('UTILITY_ECONOMICO','UTILITY_COMPLETO')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=EXCLUDED.detail, monthly_price=NULL, sort_order=70, towing_radius_km=NULL;

-- Caminhões: reboque por colisão com quilometragem livre.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, towing_radius_km)
SELECT p.id, c.id, 'INCLUDED', 'Quilometragem livre em caso de colisão', NULL, 70, NULL
FROM plans p JOIN coverages c ON c.code='TOW_COLLISION'
WHERE p.code IN ('TRUCK_OURO_SDT','TRUCK_OURO_PLUS','TRUCK_DIAMANTE_PLUS')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED', detail=EXCLUDED.detail, monthly_price=NULL, sort_order=70, towing_radius_km=NULL;

-- Plano R/F de carros nacionais pode ser criado pelo Admin e não possui código fixo no seed.
-- Quando existir, reconhecemos pelo nome e mantemos o serviço sem inventar quilometragem.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, towing_radius_km)
SELECT p.id, c.id, 'INCLUDED', 'Quilometragem conforme configuração do plano', NULL, 70, NULL
FROM plans p
JOIN vehicle_categories vc ON vc.id=p.category_id AND vc.code='CAR_NATIONAL'
JOIN coverages c ON c.code='TOW_COLLISION'
WHERE lower(p.name) LIKE '%roubo%' AND lower(p.name) LIKE '%furto%'
ON CONFLICT (plan_id, coverage_id) DO NOTHING;

-- Reboque por pane: integrado nos automóveis/utilitários/importados/caminhões,
-- opcional (R$15/mês, 300 km) nas motos Completo e R/F. Promocional não recebe.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='TOW_BREAKDOWN';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, towing_radius_km)
SELECT p.id, c.id, 'INCLUDED', x.detail, NULL, 80, x.km
FROM (VALUES
    ('CAR_ECONOMICO',500,'Pane mecânica ou elétrica'),
    ('CAR_COMPLETO',1000,'Pane mecânica ou elétrica'),
    ('UTILITY_ECONOMICO',500,'Pane mecânica ou elétrica'),
    ('UTILITY_COMPLETO',1000,'Pane mecânica ou elétrica'),
    ('CAR_IMPORTED',500,'Pane mecânica ou elétrica'),
    ('TRUCK_OURO_SDT',NULL,'1 acionamento mensal por pane mecânica ou elétrica; quilometragem editável pelo ADM'),
    ('TRUCK_OURO_PLUS',500,'1 acionamento mensal por pane mecânica ou elétrica'),
    ('TRUCK_DIAMANTE_PLUS',1000,'1 acionamento mensal por pane mecânica ou elétrica')
) AS x(plan_code,km,detail)
JOIN plans p ON p.code=x.plan_code JOIN coverages c ON c.code='TOW_BREAKDOWN'
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=80,towing_radius_km=EXCLUDED.towing_radius_km;

UPDATE coverages SET optional_vehicle_categories='MOTORCYCLE_OVER_300,MOTORCYCLE_UP_TO_300' WHERE code='TOW_BREAKDOWN';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order, towing_radius_km)
SELECT p.id, c.id, 'OPTIONAL', '1 acionamento mensal; máximo de 6 por ano', 15.00, 80, 300
FROM plans p JOIN coverages c ON c.code='TOW_BREAKDOWN'
WHERE p.code IN ('MOTO_COMPLETO_NE','MOTO_COMPLETO_CAPITAL','MOTO_RF_NE','MOTO_RF_CAPITAL','MOTO_RF_OVER_NE','MOTO_RF_OVER_CAPITAL')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='OPTIONAL', detail=EXCLUDED.detail, monthly_price=15.00, sort_order=80, towing_radius_km=300;

-- Veículo reserva / moto reserva.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='VEHICLE_REPLACEMENT';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED','10 diárias',NULL,90
FROM plans p JOIN coverages c ON c.code='VEHICLE_REPLACEMENT'
WHERE p.code IN ('CAR_COMPLETO','UTILITY_COMPLETO')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail='10 diárias',monthly_price=NULL,sort_order=90;

DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='MOTO_REPLACEMENT';
UPDATE coverages SET optional_vehicle_categories='MOTORCYCLE_UP_TO_300' WHERE code='MOTO_REPLACEMENT';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'OPTIONAL','30 diárias de moto reserva',10.00,90
FROM plans p JOIN coverages c ON c.code='MOTO_REPLACEMENT'
WHERE p.code IN ('MOTO_COMPLETO_NE','MOTO_COMPLETO_CAPITAL')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='OPTIONAL',detail='30 diárias de moto reserva',monthly_price=10.00,sort_order=90;

-- Vidros 50%: Econômico 1 peça/ano; Completo ilimitado. Caminhões permanecem 1/ano.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='GLASS';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED',x.detail,NULL,100
FROM (VALUES
 ('CAR_ECONOMICO','50% do valor; 1 peça por ano'),
 ('CAR_COMPLETO','50% do valor; acionamentos ilimitados conforme regulamento'),
 ('UTILITY_ECONOMICO','50% do valor; 1 peça por ano'),
 ('UTILITY_COMPLETO','50% do valor; acionamentos ilimitados conforme regulamento'),
 ('TRUCK_OURO_SDT','50% do valor; 1 acionamento por ano'),
 ('TRUCK_OURO_PLUS','50% do valor; 1 acionamento por ano'),
 ('TRUCK_DIAMANTE_PLUS','50% do valor; 1 acionamento por ano')
) AS x(plan_code,detail)
JOIN plans p ON p.code=x.plan_code JOIN coverages c ON c.code='GLASS'
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=100;

-- Borracheiro, chaveiro, hospedagem e táxi.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='TIRE';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED','Auxílio borracheiro',NULL,110 FROM plans p JOIN coverages c ON c.code='TIRE'
WHERE p.code IN ('CAR_ECONOMICO','CAR_COMPLETO','UTILITY_ECONOMICO','UTILITY_COMPLETO','CAR_IMPORTED')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=110;

DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='LOCKSMITH';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED','Limite de R$ 150,00',NULL,120 FROM plans p JOIN coverages c ON c.code='LOCKSMITH'
WHERE p.code IN ('CAR_ECONOMICO','CAR_COMPLETO','UTILITY_ECONOMICO','UTILITY_COMPLETO','CAR_IMPORTED')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=120;

DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='LODGING';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED','Auxílio hospedagem',NULL,130 FROM plans p JOIN coverages c ON c.code='LODGING'
WHERE p.code IN ('CAR_ECONOMICO','CAR_COMPLETO','UTILITY_ECONOMICO','UTILITY_COMPLETO','CAR_IMPORTED','TRUCK_OURO_SDT','TRUCK_OURO_PLUS','TRUCK_DIAMANTE_PLUS')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=130;

DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code='TAXI';
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED','Limite de R$ 150,00',NULL,140 FROM plans p JOIN coverages c ON c.code='TAXI'
WHERE p.code IN ('CAR_ECONOMICO','CAR_COMPLETO','UTILITY_ECONOMICO','UTILITY_COMPLETO','CAR_IMPORTED')
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=140;

-- Assistência 24h e cobertura nacional somente Diamante Plus.
DELETE FROM plan_coverages pc USING coverages c WHERE pc.coverage_id=c.id AND c.code IN ('ASSISTANCE_24H','NATIONAL');
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED',c.name,NULL,150 + CASE WHEN c.code='NATIONAL' THEN 1 ELSE 0 END
FROM plans p JOIN coverages c ON c.code IN ('ASSISTANCE_24H','NATIONAL')
WHERE p.code='TRUCK_DIAMANTE_PLUS'
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=EXCLUDED.sort_order;

-- Benefícios ao associado: todos os planos.
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'INCLUDED',x.detail,NULL,x.sort_order
FROM plans p
CROSS JOIN (VALUES
 ('SAMVIDA','Assistência médica SAMVIDA',160),
 ('PHARMACY','Descontos em medicamentos nas Drogarias Sampaio',170),
 ('CLUB','Clube de vantagens para associado ativo pagante',180)
) AS x(code,detail,sort_order)
JOIN coverages c ON c.code=x.code
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='INCLUDED',detail=EXCLUDED.detail,monthly_price=NULL,sort_order=EXCLUDED.sort_order;

-- Funerais continuam opcionais para todos os tipos e com os valores já definidos.
UPDATE coverages SET optional_vehicle_categories=(SELECT string_agg(code,',' ORDER BY code) FROM vehicle_categories)
WHERE code IN ('FUNERAL','FUNERAL_FAMILY');
INSERT INTO plan_coverages(plan_id, coverage_id, status, detail, monthly_price, sort_order)
SELECT p.id,c.id,'OPTIONAL',x.detail,x.price,x.sort_order
FROM plans p
CROSS JOIN (VALUES
 ('FUNERAL','Cobertura de R$ 3.000,00',5.00::numeric,190),
 ('FUNERAL_FAMILY','Associado(a), cônjuge e um filho; R$ 3.000,00 para cada',12.00::numeric,191)
) AS x(code,detail,price,sort_order)
JOIN coverages c ON c.code=x.code
ON CONFLICT (plan_id, coverage_id) DO UPDATE SET status='OPTIONAL',detail=EXCLUDED.detail,monthly_price=EXCLUDED.monthly_price,sort_order=EXCLUDED.sort_order;

-- Garante que a regra especial do Plano Completo siga intacta.
DELETE FROM plan_coverages pc USING coverages c, plans p
WHERE pc.coverage_id=c.id AND pc.plan_id=p.id
  AND c.code IN ('NATURAL_PHENOMENA','SMALL_REPAIRS')
  AND p.code NOT IN ('CAR_COMPLETO','UTILITY_COMPLETO');

-- Não há atualização destrutiva de quotation_coverage_snapshots: cotações antigas
-- preservam exatamente as coberturas aceitas na data em que foram emitidas.
