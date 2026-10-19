-- Garante idempotência do envio retomável: uma posição de arquivo por vistoria e tipo.
WITH duplicated AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY inspection_id, asset_type, sort_order
               ORDER BY id
           ) AS row_number
    FROM inspection_assets
)
DELETE FROM inspection_assets
WHERE id IN (SELECT id FROM duplicated WHERE row_number > 1);

CREATE UNIQUE INDEX IF NOT EXISTS uq_inspection_asset_type_order
    ON inspection_assets(inspection_id, asset_type, sort_order);
