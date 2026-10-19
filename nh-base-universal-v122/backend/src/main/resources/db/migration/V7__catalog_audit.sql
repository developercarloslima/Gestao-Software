CREATE TABLE catalog_change_audit (
    id BIGSERIAL PRIMARY KEY,
    item_type VARCHAR(30) NOT NULL,
    item_id BIGINT NOT NULL,
    description VARCHAR(260) NOT NULL,
    old_value NUMERIC(14,2),
    new_value NUMERIC(14,2),
    changed_by VARCHAR(160) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_catalog_audit_changed_at ON catalog_change_audit(changed_at DESC);
