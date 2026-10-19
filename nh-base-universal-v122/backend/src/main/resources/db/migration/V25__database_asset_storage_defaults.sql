-- O Retrato NH passa a usar exclusivamente o PostgreSQL para novos arquivos.
ALTER TABLE inspection_assets
    ALTER COLUMN storage_kind SET DEFAULT 'DATABASE';

-- Metadados DATABASE sem conteúdo não podem ser considerados arquivos enviados.
UPDATE inspection_assets asset
   SET purged_at = COALESCE(asset.purged_at, NOW())
 WHERE asset.storage_kind = 'DATABASE'
   AND asset.purged_at IS NULL
   AND NOT EXISTS (
       SELECT 1 FROM inspection_asset_contents content WHERE content.asset_id = asset.id
   );
