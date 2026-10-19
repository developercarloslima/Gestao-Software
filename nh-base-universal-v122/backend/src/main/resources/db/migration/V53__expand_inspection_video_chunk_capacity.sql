-- V16: vídeos nativos do iPhone não possuem limite comercial de MB.
-- O backend e o frontend já aceitam até 4096 partes; esta migration alinha
-- a restrição física criada originalmente na V27 (512 partes).

DO $$
DECLARE
    current_constraint TEXT;
BEGIN
    SELECT c.conname
      INTO current_constraint
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
      JOIN pg_namespace n ON n.oid = t.relnamespace
     WHERE n.nspname = current_schema()
       AND t.relname = 'inspection_asset_blobs'
       AND c.contype = 'c'
       AND pg_get_constraintdef(c.oid) ILIKE '%total_chunks%'
     LIMIT 1;

    IF current_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE inspection_asset_blobs DROP CONSTRAINT %I', current_constraint);
    END IF;
END
$$;

ALTER TABLE inspection_asset_blobs
    ADD CONSTRAINT chk_inspection_asset_blobs_total_chunks_v16
    CHECK (total_chunks BETWEEN 1 AND 4096);
