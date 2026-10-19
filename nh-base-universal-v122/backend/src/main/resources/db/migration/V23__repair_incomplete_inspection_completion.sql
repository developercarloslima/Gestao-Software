-- Corrige registros antigos que foram marcados como concluídos sem arquivos confirmados.
UPDATE inspection_requests ir
SET status = 'WAITING_FILES',
    completed_at = NULL,
    reviewed_at = NULL,
    report_file_id = NULL,
    report_url = NULL
WHERE status IN ('CREATED', 'UPLOADING_FILES', 'COMPLETED', 'UNDER_REVIEW')
  AND NOT EXISTS (
      SELECT 1
      FROM inspection_assets ia
      WHERE ia.inspection_id = ir.id
  );

-- Se há arquivos, mas não existe relatório confirmado, o envio ainda está em andamento.
UPDATE inspection_requests ir
SET status = 'UPLOADING_FILES',
    completed_at = NULL
WHERE status IN ('COMPLETED', 'UNDER_REVIEW')
  AND EXISTS (
      SELECT 1
      FROM inspection_assets ia
      WHERE ia.inspection_id = ir.id
  )
  AND (report_file_id IS NULL OR report_url IS NULL OR drive_folder_id IS NULL OR drive_folder_url IS NULL);
