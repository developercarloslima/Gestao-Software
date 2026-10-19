UPDATE inspection_requests ir
SET status = 'WAITING_FILES',
    completed_at = NULL
WHERE status NOT IN ('CANCELLED', 'EXPIRED')
  AND NOT EXISTS (
      SELECT 1
      FROM inspection_assets ia
      WHERE ia.inspection_id = ir.id
  );

COMMENT ON COLUMN inspection_requests.status IS
    'WAITING_FILES indica que a solicitação chegou à análise, mas o associado ainda não enviou os arquivos.';
