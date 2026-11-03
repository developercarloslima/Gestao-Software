-- A base multiempresa usa db/tenant (independente das migrations db/migration).
-- Preserva vistorias anteriores; somente novas solicitações recebem associação verificada.
ALTER TABLE inspection_requests
  ADD COLUMN IF NOT EXISTS associate_id UUID;

DO $$ BEGIN
  ALTER TABLE inspection_requests
    ADD CONSTRAINT fk_inspection_requests_associate
    FOREIGN KEY (associate_id) REFERENCES associates(id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS idx_inspection_requests_associate
  ON inspection_requests(associate_id);
