CREATE TABLE coverage_rules (
    id BIGSERIAL PRIMARY KEY,
    coverage_id BIGINT NOT NULL REFERENCES coverages(id) ON DELETE CASCADE,
    category_code VARCHAR(50),
    min_fipe NUMERIC(14,2) NOT NULL DEFAULT 0,
    max_fipe NUMERIC(14,2),
    normal_amount NUMERIC(14,2) NOT NULL,
    discounted_amount NUMERIC(14,2),
    sort_order INTEGER NOT NULL DEFAULT 100,
    CONSTRAINT chk_coverage_rule_min_fipe CHECK (min_fipe >= 0),
    CONSTRAINT chk_coverage_rule_max_fipe CHECK (max_fipe IS NULL OR max_fipe >= min_fipe),
    CONSTRAINT chk_coverage_rule_normal_amount CHECK (normal_amount >= 0),
    CONSTRAINT chk_coverage_rule_discounted_amount CHECK (discounted_amount IS NULL OR discounted_amount >= 0),
    CONSTRAINT chk_coverage_rule_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX idx_coverage_rules_coverage ON coverage_rules(coverage_id, sort_order, id);
CREATE INDEX idx_coverage_rules_context ON coverage_rules(category_code, min_fipe, max_fipe);

ALTER TABLE quotations
    ADD COLUMN auction_or_chassis_remarked BOOLEAN,
    ADD COLUMN indemnity_fipe_percent INTEGER NOT NULL DEFAULT 100;

ALTER TABLE quotations
    ADD CONSTRAINT chk_quotations_indemnity_fipe_percent
    CHECK (indemnity_fipe_percent BETWEEN 1 AND 100);
