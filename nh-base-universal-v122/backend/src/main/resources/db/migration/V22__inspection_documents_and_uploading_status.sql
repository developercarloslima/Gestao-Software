-- Solicitações que já possuem algum arquivo não devem continuar exibidas como "Aguardando arquivos".
UPDATE inspection_requests ir
SET status = 'UPLOADING_FILES'
WHERE status IN ('WAITING_FILES', 'CREATED')
  AND EXISTS (
      SELECT 1
      FROM inspection_assets ia
      WHERE ia.inspection_id = ir.id
  );

COMMENT ON COLUMN inspection_requests.status IS
    'WAITING_FILES: nenhum arquivo; UPLOADING_FILES: envio iniciado; COMPLETED: todos os arquivos e relatório confirmados no Drive.';
