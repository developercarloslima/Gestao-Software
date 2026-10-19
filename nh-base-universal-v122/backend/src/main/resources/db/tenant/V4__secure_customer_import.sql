-- Tenant-only: a database per company. Do not copy this migration into the legacy db/migration folder.
-- Raw CSV is encrypted by the application with a tenant-specific external key before storage.
CREATE TABLE tenant_customer_import_batches (
  id UUID PRIMARY KEY,
  original_filename VARCHAR(160) NOT NULL,
  sha256 CHAR(64) NOT NULL UNIQUE,
  encrypted_original BYTEA NOT NULL,
  iv BYTEA NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('READY','INVALID','IMPORTED')),
  total_rows INTEGER NOT NULL CHECK (total_rows>=0),
  valid_rows INTEGER NOT NULL CHECK (valid_rows>=0),
  invalid_rows INTEGER NOT NULL CHECK (invalid_rows>=0),
  imported_count INTEGER NOT NULL DEFAULT 0,
  skipped_count INTEGER NOT NULL DEFAULT 0,
  legal_attestation BOOLEAN NOT NULL,
  uploaded_by VARCHAR(160) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  imported_by VARCHAR(160),
  imported_at TIMESTAMPTZ
);
CREATE INDEX idx_tenant_customer_import_created ON tenant_customer_import_batches(created_at DESC);
CREATE TABLE tenant_customer_import_links (
  batch_id UUID NOT NULL REFERENCES tenant_customer_import_batches(id) ON DELETE RESTRICT,
  source_line INTEGER NOT NULL CHECK (source_line>=2),
  associate_id UUID NOT NULL REFERENCES associates(id) ON DELETE RESTRICT,
  source_external_code VARCHAR(160),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (batch_id,source_line)
);
CREATE INDEX idx_tenant_customer_import_links_associate ON tenant_customer_import_links(associate_id);

-- Auditoria separada para acessos aos arquivos originais sensíveis.
CREATE TABLE tenant_customer_import_access_audit (
  id UUID PRIMARY KEY,
  batch_id UUID NOT NULL REFERENCES tenant_customer_import_batches(id) ON DELETE RESTRICT,
  operation VARCHAR(32) NOT NULL CHECK(operation IN ('ORIGINAL_DOWNLOAD')),
  actor VARCHAR(160) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tenant_import_access_audit_batch ON tenant_customer_import_access_audit(batch_id,created_at DESC);
