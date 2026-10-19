-- Garante que a regra atual de Supervisão também alcance todo o histórico legado.
-- Regra atual:
--   * Analista não dá decisão final; registra a situação do cadastro.
--   * Supervisão/Admin podem assumir "Cadastro realizado" e então aprovar/rejeitar.
--   * Somente SUPERVISION_ANALYSIS e ADMIN_SUPERVISION representam decisão final válida.
--
-- Esta migration NÃO altera decisões finais já tomadas por Supervisão/Admin.
-- Ela apenas reabre registros legados que foram encerrados por analista/fluxo antigo.

-- 1) Aprovações antigas sem decisão final de Supervisão passam a representar
--    "Cadastro realizado" e aguardam a decisão final da Supervisão/Admin.
UPDATE inspection_requests
   SET status = 'UNDER_REVIEW',
       analysis_stage = 'SUPERVISION_QUEUE',
       registration_completed_at = COALESCE(registration_completed_at, reviewed_at, completed_at, created_at),
       registration_completed_by_name = COALESCE(
           NULLIF(btrim(registration_completed_by_name), ''),
           NULLIF(btrim(reviewed_by_name), ''),
           NULLIF(btrim(assigned_analyst_name), ''),
           'Equipe de Análise'
       ),
       decision_message_sent_at = NULL
 WHERE status = 'APPROVED'
   AND COALESCE(reviewed_by_role, '') NOT IN ('SUPERVISION_ANALYSIS', 'ADMIN_SUPERVISION');

-- 2) Rejeições antigas feitas fora da Supervisão deixam de ser consideradas
--    decisão final. Elas retornam para a fila operacional, onde a Supervisão/Admin
--    pode abrir a vistoria, assumir "Cadastro realizado" e decidir novamente.
UPDATE inspection_requests
   SET status = 'UNDER_REVIEW',
       analysis_stage = 'ANALYST_QUEUE',
       registration_completed_at = NULL,
       registration_completed_by_name = NULL,
       decision_message_sent_at = NULL
 WHERE status = 'REJECTED'
   AND COALESCE(reviewed_by_role, '') NOT IN ('SUPERVISION_ANALYSIS', 'ADMIN_SUPERVISION');

-- 3) Corrige históricos que ficaram marcados como FINISHED sem uma decisão final
--    válida de Supervisão/Admin. Se já existe evidência de cadastro realizado,
--    vai direto para Aguardando Supervisão; caso contrário, volta para a fila dos analistas.
UPDATE inspection_requests
   SET status = 'UNDER_REVIEW',
       analysis_stage = CASE
           WHEN registration_completed_at IS NOT NULL
                OR NULLIF(btrim(registration_completed_by_name), '') IS NOT NULL
             THEN 'SUPERVISION_QUEUE'
           ELSE 'ANALYST_QUEUE'
       END,
       decision_message_sent_at = NULL
 WHERE analysis_stage = 'FINISHED'
   AND status NOT IN ('CANCELLED', 'EXPIRED')
   AND COALESCE(reviewed_by_role, '') NOT IN ('SUPERVISION_ANALYSIS', 'ADMIN_SUPERVISION');

-- 4) Garante responsável visível nas vistorias históricas ainda em fluxo,
--    usando o vínculo atual do consultor somente quando o histórico não possui analista salvo.
UPDATE inspection_requests ir
   SET assigned_analyst_id = analyst.id,
       assigned_analyst_name = analyst.name
  FROM consultants consultant
  JOIN consultants analyst ON analyst.id = consultant.assigned_analyst_id
 WHERE ir.consultant_id = consultant.id
   AND ir.analysis_stage IN ('ANALYST_QUEUE', 'ANALYST_PENDING', 'SUPERVISION_QUEUE')
   AND ir.assigned_analyst_id IS NULL;

UPDATE inspection_requests ir
   SET assigned_analyst_name = analyst.name
  FROM consultants analyst
 WHERE ir.assigned_analyst_id = analyst.id
   AND ir.analysis_stage IN ('ANALYST_QUEUE', 'ANALYST_PENDING', 'SUPERVISION_QUEUE')
   AND (ir.assigned_analyst_name IS NULL OR btrim(ir.assigned_analyst_name) = '');

COMMENT ON COLUMN inspection_requests.analysis_stage IS
    'Fila operacional. Registros históricos sem decisão final válida de Supervisão/Admin são normalizados por V47 para que também sigam o fluxo atual.';
