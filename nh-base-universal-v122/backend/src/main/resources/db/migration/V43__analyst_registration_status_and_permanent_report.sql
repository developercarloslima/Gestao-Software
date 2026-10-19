-- Ajusta o fluxo da Equipe de Análise e torna o relatório consolidado permanente.
-- Aprovações históricas feitas por ANALISTAS passam a representar "Cadastro feito"
-- e seguem para a fila da Supervisão, que passa a ser a única responsável pela decisão final.

WITH analyst_approved AS (
    SELECT ir.id,
           ir.reviewed_by_collaborator_id,
           ir.reviewed_by_name,
           ir.reviewed_at,
           COALESCE(c.id, named_analyst.id) AS analyst_id,
           COALESCE(c.name, named_analyst.name) AS analyst_name
      FROM inspection_requests ir
      LEFT JOIN consultants c
        ON c.id = ir.reviewed_by_collaborator_id
      LEFT JOIN LATERAL (
          SELECT candidate.id, candidate.name
            FROM consultants candidate
           WHERE candidate.collaborator_role = 'ANALYST'
             AND ir.reviewed_by_name IS NOT NULL
             AND lower(btrim(candidate.name)) = lower(btrim(ir.reviewed_by_name))
           ORDER BY candidate.active DESC, candidate.created_at ASC
           LIMIT 1
      ) named_analyst ON TRUE
     WHERE ir.status = 'APPROVED'
       AND (
            ir.reviewed_by_role = 'ANALYST'
            OR c.collaborator_role = 'ANALYST'
            OR (
                ir.reviewed_by_role IS NULL
                AND ir.reviewed_by_name IS NOT NULL
                AND btrim(ir.reviewed_by_name) <> ''
                AND lower(btrim(ir.reviewed_by_name)) <> lower('Análise feita pelo administrador')
            )
       )
)
UPDATE inspection_requests ir
   SET status = 'UNDER_REVIEW',
       analysis_stage = 'SUPERVISION_QUEUE',
       assigned_analyst_id = COALESCE(ir.assigned_analyst_id, aa.analyst_id),
       assigned_analyst_name = COALESCE(ir.assigned_analyst_name, aa.analyst_name, aa.reviewed_by_name),
       registration_completed_at = COALESCE(ir.registration_completed_at, aa.reviewed_at, ir.completed_at, ir.created_at),
       registration_completed_by_name = COALESCE(ir.registration_completed_by_name, aa.analyst_name, aa.reviewed_by_name, 'Equipe de Análise'),
       decision_message_sent_at = NULL
  FROM analyst_approved aa
 WHERE ir.id = aa.id;

-- Relatórios já gerados e ainda preservados deixam de expirar.
-- Arquivos que já foram fisicamente purgados não são recriados por SQL.
UPDATE inspection_assets
   SET expires_at = NULL
 WHERE asset_type = 'REPORT'
   AND purged_at IS NULL;

COMMENT ON COLUMN inspection_requests.registration_completed_at IS
    'Data em que a Equipe de Análise marcou Cadastro feito e encaminhou a vistoria para a Supervisão.';

COMMENT ON COLUMN inspection_assets.expires_at IS
    'Prazo de retenção do arquivo original. NULL para o relatório PDF consolidado, que é permanente e não possui validade.';
