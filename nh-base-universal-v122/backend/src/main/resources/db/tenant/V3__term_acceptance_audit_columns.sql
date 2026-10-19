-- NH tenant v119: somente DDL; sem dados, usuários ou aceites históricos de outra empresa.
-- Não reutilizar a migração legada V87: seu backfill é exclusivamente da instalação antiga.
ALTER TABLE inspection_responsibility_terms
  ADD COLUMN IF NOT EXISTS acceptance_source VARCHAR(32) NOT NULL DEFAULT 'STANDALONE',
  ADD COLUMN IF NOT EXISTS original_dossier_sha256 VARCHAR(64);
COMMENT ON COLUMN inspection_responsibility_terms.acceptance_source IS
  'STANDALONE para aceite deste termo; DOSSIER_ACCEPTANCE apenas em restaurações históricas verificadas';
