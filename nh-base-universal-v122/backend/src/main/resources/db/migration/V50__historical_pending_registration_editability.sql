-- V13: garante que vistorias/cotações históricas ainda pendentes sigam o fluxo atual.
-- Objetivos:
--   1) ANALYST_QUEUE/ANALYST_PENDING nunca devem carregar "Cadastro feito" legado;
--   2) SUPERVISION_QUEUE sem evidência real de cadastro volta para Cadastro;
--   3) registros abertos marcados FINISHED por fluxos antigos são reabertos;
--   4) vistorias abertas sem analista válido herdam o vínculo atual do consultor.

-- A etapa operacional do analista é a fonte de verdade para itens ainda não concluídos.
UPDATE inspection_requests
   SET registration_completed_at = NULL,
       registration_completed_by_name = NULL
 WHERE analysis_stage IN ('ANALYST_QUEUE', 'ANALYST_PENDING')
   AND (registration_completed_at IS NOT NULL
        OR NULLIF(btrim(registration_completed_by_name), '') IS NOT NULL);

-- SUPERVISION_QUEUE exige evidência de "Cadastro feito". Se ela não existe,
-- o registro é histórico pendente e deve voltar para o Cadastro.
UPDATE inspection_requests
   SET analysis_stage = CASE
           WHEN status IN ('WAITING_FILES', 'UPLOADING_FILES', 'CREATED') THEN 'ANALYST_PENDING'
           ELSE 'ANALYST_QUEUE'
       END,
       registration_completed_at = NULL,
       registration_completed_by_name = NULL,
       decision_message_sent_at = NULL
 WHERE analysis_stage = 'SUPERVISION_QUEUE'
   AND registration_completed_at IS NULL
   AND COALESCE(reviewed_by_role, '') NOT IN ('SUPERVISION_ANALYSIS', 'ADMIN_SUPERVISION');

-- Fluxos muito antigos podiam encerrar a etapa sem uma decisão final real.
UPDATE inspection_requests
   SET analysis_stage = CASE
           WHEN status IN ('WAITING_FILES', 'UPLOADING_FILES', 'CREATED') THEN 'ANALYST_PENDING'
           ELSE 'ANALYST_QUEUE'
       END,
       registration_completed_at = NULL,
       registration_completed_by_name = NULL,
       decision_message_sent_at = NULL
 WHERE analysis_stage = 'FINISHED'
   AND status NOT IN ('APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')
   AND COALESCE(reviewed_by_role, '') NOT IN ('SUPERVISION_ANALYSIS', 'ADMIN_SUPERVISION');

-- Se o analista histórico deixou de ser válido, usa a distribuição atual do consultor.
WITH reassignment AS (
    SELECT ir.id,
           current_analyst.id AS analyst_id,
           current_analyst.name AS analyst_name
      FROM inspection_requests ir
      JOIN consultants owner ON owner.id = ir.consultant_id
      JOIN consultants current_analyst ON current_analyst.id = owner.assigned_analyst_id
      LEFT JOIN consultants stored_analyst ON stored_analyst.id = ir.assigned_analyst_id
     WHERE ir.analysis_stage IN ('ANALYST_QUEUE', 'ANALYST_PENDING')
       AND current_analyst.active = TRUE
       AND current_analyst.collaborator_role = 'ANALYST'
       AND (
            ir.assigned_analyst_id IS NULL
            OR stored_analyst.id IS NULL
            OR stored_analyst.active = FALSE
            OR stored_analyst.collaborator_role <> 'ANALYST'
       )
)
UPDATE inspection_requests ir
   SET assigned_analyst_id = r.analyst_id,
       assigned_analyst_name = r.analyst_name
  FROM reassignment r
 WHERE ir.id = r.id;

-- Mantém o nome textual consistente com o responsável salvo.
UPDATE inspection_requests ir
   SET assigned_analyst_name = analyst.name
  FROM consultants analyst
 WHERE ir.assigned_analyst_id = analyst.id
   AND ir.analysis_stage IN ('ANALYST_QUEUE', 'ANALYST_PENDING')
   AND (ir.assigned_analyst_name IS NULL OR btrim(ir.assigned_analyst_name) = '' OR ir.assigned_analyst_name <> analyst.name);

COMMENT ON COLUMN inspection_requests.analysis_stage IS
    'Fila operacional. V50 normaliza históricos pendentes para que FIPE/mensalidade e ações do Cadastro permaneçam disponíveis antes de Cadastro feito.';
