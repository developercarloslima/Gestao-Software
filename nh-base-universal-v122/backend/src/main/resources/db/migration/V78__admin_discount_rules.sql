-- V78 — descontos totalmente administráveis pelo painel de Cota/Vistoria.
-- O percentual continua gravado na cotação para preservar histórico, mas as
-- regras de elegibilidade, planos, componentes do preço e efeitos em benefícios
-- passam a vir do catálogo administrativo.

ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotation_discount_percent;
ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_discount_percent
    CHECK (discount_percent BETWEEN 0 AND 100);

ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotation_discount_branding;
ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotation_rear_window_branding;
ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_rear_window_branding
    CHECK (rear_window_branding IN ('NOT_APPLICABLE', 'ANY_PERFORATED', 'NH_AND_OTHER_COMPANY', 'NH_ONLY'));

CREATE TABLE discount_rules (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    percent INTEGER NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 100,
    min_vehicle_age_years INTEGER,
    max_vehicle_age_years INTEGER,
    min_fipe NUMERIC(14,2),
    max_fipe NUMERIC(14,2),
    requires_rear_window_perforation BOOLEAN NOT NULL DEFAULT FALSE,
    apply_to_base BOOLEAN NOT NULL DEFAULT TRUE,
    apply_to_mandatory_fee BOOLEAN NOT NULL DEFAULT TRUE,
    apply_to_optionals BOOLEAN NOT NULL DEFAULT TRUE,
    notes VARCHAR(600),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_discount_rules_percent CHECK (percent BETWEEN 1 AND 100),
    CONSTRAINT chk_discount_rules_order CHECK (display_order >= 0),
    CONSTRAINT chk_discount_rules_min_age CHECK (min_vehicle_age_years IS NULL OR min_vehicle_age_years >= 0),
    CONSTRAINT chk_discount_rules_max_age CHECK (max_vehicle_age_years IS NULL OR max_vehicle_age_years >= 0),
    CONSTRAINT chk_discount_rules_age_range CHECK (
        min_vehicle_age_years IS NULL OR max_vehicle_age_years IS NULL OR max_vehicle_age_years >= min_vehicle_age_years
    ),
    CONSTRAINT chk_discount_rules_min_fipe CHECK (min_fipe IS NULL OR min_fipe >= 0),
    CONSTRAINT chk_discount_rules_max_fipe CHECK (max_fipe IS NULL OR max_fipe >= 0),
    CONSTRAINT chk_discount_rules_fipe_range CHECK (
        min_fipe IS NULL OR max_fipe IS NULL OR max_fipe >= min_fipe
    )
);

CREATE TABLE discount_rule_plans (
    discount_rule_id BIGINT NOT NULL REFERENCES discount_rules(id) ON DELETE CASCADE,
    plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    PRIMARY KEY (discount_rule_id, plan_id)
);
CREATE INDEX idx_discount_rule_plans_plan ON discount_rule_plans(plan_id, discount_rule_id);

CREATE TABLE discount_rule_benefits (
    id BIGSERIAL PRIMARY KEY,
    discount_rule_id BIGINT NOT NULL REFERENCES discount_rules(id) ON DELETE CASCADE,
    coverage_id BIGINT NOT NULL REFERENCES coverages(id) ON DELETE CASCADE,
    action VARCHAR(24) NOT NULL,
    detail_override VARCHAR(240),
    amount_override NUMERIC(14,2),
    CONSTRAINT uq_discount_rule_benefit UNIQUE(discount_rule_id, coverage_id),
    CONSTRAINT chk_discount_rule_benefit_action CHECK (action IN ('REMOVE', 'FORCE_INCLUDE')),
    CONSTRAINT chk_discount_rule_benefit_amount CHECK (amount_override IS NULL OR amount_override >= 0)
);
CREATE INDEX idx_discount_rule_benefits_rule ON discount_rule_benefits(discount_rule_id, coverage_id);

-- Regras comerciais vigentes em 03/10/2026.
INSERT INTO discount_rules(name, percent, active, display_order, max_vehicle_age_years,
                           requires_rear_window_perforation, apply_to_base, apply_to_mandatory_fee,
                           apply_to_optionals, notes)
VALUES
    ('Desconto 5%', 5, TRUE, 10, NULL, FALSE, TRUE, TRUE, TRUE,
     'Disponível para carros conforme os planos vinculados.'),
    ('Desconto 10%', 10, TRUE, 20, NULL, FALSE, TRUE, TRUE, TRUE,
     'Desconto padrão. Em motocicletas este é o único percentual habilitado.'),
    ('Desconto 15%', 15, TRUE, 30, 3, FALSE, TRUE, TRUE, TRUE,
     'Restrito a veículos com até 3 anos de uso.'),
    ('Desconto 30%', 30, TRUE, 40, NULL, TRUE, TRUE, TRUE, TRUE,
     'Exige perfurado no vigia traseiro, independentemente da divisão das logomarcas.')
ON CONFLICT (percent) DO NOTHING;

-- 10% em todos os planos. Assim motocicletas ficam travadas somente em 10%.
INSERT INTO discount_rule_plans(discount_rule_id, plan_id)
SELECT d.id, p.id
FROM discount_rules d
JOIN plans p ON TRUE
WHERE d.percent = 10
ON CONFLICT DO NOTHING;

-- 5%, 15% e 30% inicialmente apenas para os planos de carros. O Admin pode ampliar/remover os vínculos no painel.
INSERT INTO discount_rule_plans(discount_rule_id, plan_id)
SELECT d.id, p.id
FROM discount_rules d
JOIN plans p ON TRUE
JOIN vehicle_categories c ON c.id = p.category_id
WHERE d.percent IN (5, 15, 30)
  AND c.code IN ('CAR_NATIONAL', 'CAR_IMPORTED')
ON CONFLICT DO NOTHING;

-- Preserva a regra comercial anterior: com desconto, estes benefícios saem.
-- Agora, porém, o Admin pode remover/alterar estes vínculos pelo painel.
INSERT INTO discount_rule_benefits(discount_rule_id, coverage_id, action)
SELECT d.id, c.id, 'REMOVE'
FROM discount_rules d
JOIN coverages c ON c.code IN ('NATURAL_PHENOMENA', 'SMALL_REPAIRS')
WHERE d.percent IN (5, 10, 15, 30)
ON CONFLICT (discount_rule_id, coverage_id) DO NOTHING;
