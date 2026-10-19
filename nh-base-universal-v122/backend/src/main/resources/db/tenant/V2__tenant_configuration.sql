-- Business configuration is NOT seeded: nothing is inherited from legacy catalogues.
CREATE TABLE IF NOT EXISTS tenant_launch_requirements (
   requirement_key VARCHAR(64) PRIMARY KEY,
   approved BOOLEAN NOT NULL DEFAULT FALSE,
   approved_by VARCHAR(180),
   approved_at TIMESTAMPTZ,
   evidence_uri VARCHAR(1024),
   updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Standard requirements are product-wide states, never customer-specific amounts.
INSERT INTO tenant_launch_requirements(requirement_key) VALUES
 ('BUSINESS_CATALOG_REVIEWED'),
 ('REGULATION_PUBLISHED'),
 ('BRAND_REVIEWED'),
 ('ADMIN_PASSWORD_ROTATED'),
 ('PAYMENT_WEBHOOK_VERIFIED'),
 ('PONTO_SETUP_REVIEWED'),
 ('BACKUP_RESTORE_VERIFIED')
ON CONFLICT DO NOTHING;
