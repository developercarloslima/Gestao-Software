-- Universal tenant: estrutura comercial regional, SEM tabelas de preços históricas.
-- Valores, faixas, coberturas e regras são preenchidos pelo ADM de CADA empresa.
ALTER TABLE price_ranges ADD COLUMN IF NOT EXISTS pricing_region VARCHAR(30);
UPDATE price_ranges SET pricing_region='NORTHEAST' WHERE pricing_region IS NULL;
ALTER TABLE price_ranges ALTER COLUMN pricing_region SET DEFAULT 'NORTHEAST';
ALTER TABLE price_ranges ALTER COLUMN pricing_region SET NOT NULL;
ALTER TABLE price_ranges DROP CONSTRAINT IF EXISTS price_ranges_plan_id_min_value_max_value_key;
ALTER TABLE price_ranges DROP CONSTRAINT IF EXISTS chk_price_ranges_pricing_region;
ALTER TABLE price_ranges ADD CONSTRAINT chk_price_ranges_pricing_region CHECK (pricing_region IN ('NORTHEAST','OUTSIDE_NORTHEAST'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_price_ranges_plan_region_range ON price_ranges(plan_id, pricing_region, min_value, max_value);
CREATE INDEX IF NOT EXISTS idx_price_ranges_plan_region_fipe ON price_ranges(plan_id, pricing_region, min_value, max_value);
ALTER TABLE quotations ADD COLUMN IF NOT EXISTS pricing_region VARCHAR(30);
UPDATE quotations SET pricing_region='NORTHEAST' WHERE pricing_region IS NULL;
ALTER TABLE quotations ALTER COLUMN pricing_region SET DEFAULT 'NORTHEAST';
ALTER TABLE quotations ALTER COLUMN pricing_region SET NOT NULL;
ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotations_pricing_region;
ALTER TABLE quotations ADD CONSTRAINT chk_quotations_pricing_region CHECK (pricing_region IN ('NORTHEAST','OUTSIDE_NORTHEAST'));
ALTER TABLE plans DROP CONSTRAINT IF EXISTS chk_plans_motorcycle_origin;
ALTER TABLE plans ADD CONSTRAINT chk_plans_motorcycle_origin CHECK (motorcycle_origin IS NULL OR motorcycle_origin IN ('NORTHEAST','CAPITAL','OUTSIDE_NORTHEAST'));
ALTER TABLE quotations DROP CONSTRAINT IF EXISTS chk_quotations_motorcycle_origin;
ALTER TABLE quotations ADD CONSTRAINT chk_quotations_motorcycle_origin CHECK (motorcycle_origin IS NULL OR motorcycle_origin IN ('NORTHEAST','CAPITAL','OUTSIDE_NORTHEAST'));
CREATE TABLE IF NOT EXISTS plan_regional_pricing_rules (
 id BIGSERIAL PRIMARY KEY,
 plan_id BIGINT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
 pricing_region VARCHAR(30) NOT NULL,
 extra_above NUMERIC(14,2), extra_step NUMERIC(14,2),
 extra_increment NUMERIC(14,2), extra_base_price NUMERIC(14,2),
 CONSTRAINT uq_plan_regional_pricing_rule UNIQUE(plan_id,pricing_region),
 CONSTRAINT chk_plan_regional_pricing_region CHECK(pricing_region IN ('NORTHEAST','OUTSIDE_NORTHEAST'))
);
ALTER TABLE quotations ADD COLUMN IF NOT EXISTS residence_cep VARCHAR(8);
ALTER TABLE quotations ADD COLUMN IF NOT EXISTS residence_address VARCHAR(600);
-- Nenhum plano, desconto, valor ou regra de terceiro da NH é inserido.
