-- Toda análise realizada pelo administrador passa a exigir decisão final da Supervisão.
-- O administrador continua podendo atuar com os mesmos poderes da Supervisão, mas por uma ação final explícita.


-- Atualiza o nome de exibição da conta administrativa principal quando ainda usa o rótulo genérico legado.
UPDATE portal_users
   SET display_name = 'Pedro Henrique'
 WHERE role = 'ADMIN'
   AND (display_name IS NULL
        OR btrim(display_name) = ''
        OR lower(btrim(display_name)) IN ('administrador principal', 'administrador'));

-- Normaliza o nome histórico do administrador principal.
UPDATE inspection_requests
   SET reviewed_by_name = 'Pedro Henrique'
 WHERE reviewed_by_role = 'ADMIN'
   AND (reviewed_by_name IS NULL
        OR btrim(reviewed_by_name) = ''
        OR lower(reviewed_by_name) IN ('administrador', 'análise feita pelo administrador', 'analise feita pelo administrador'));

UPDATE inspection_requests
   SET supervision_note_by_name = 'Pedro Henrique'
 WHERE supervision_note_by_name IS NOT NULL
   AND lower(btrim(supervision_note_by_name)) = 'administrador';

-- Decisões finais históricas feitas pelo Admin voltam para a fila da Supervisão.
-- Os registros e evidências anteriores são preservados para auditoria; somente a decisão final volta a ficar pendente.
UPDATE inspection_requests
   SET status = 'UNDER_REVIEW',
       analysis_stage = 'SUPERVISION_QUEUE',
       registration_completed_at = COALESCE(registration_completed_at, reviewed_at, completed_at, created_at),
       registration_completed_by_name = COALESCE(NULLIF(btrim(registration_completed_by_name), ''), 'Pedro Henrique'),
       reviewed_by_name = 'Pedro Henrique',
       reviewed_by_role = 'ADMIN_ANALYSIS',
       decision_message_sent_at = NULL
 WHERE reviewed_by_role = 'ADMIN'
   AND (analysis_stage = 'FINISHED' OR status IN ('APPROVED', 'REJECTED'));

-- Demais análises administrativas passam a usar um papel distinto da decisão final de supervisão.
UPDATE inspection_requests
   SET reviewed_by_name = 'Pedro Henrique',
       reviewed_by_role = 'ADMIN_ANALYSIS'
 WHERE reviewed_by_role = 'ADMIN';

COMMENT ON COLUMN inspection_requests.reviewed_by_role IS
    'Origem da última análise/decisão: ANALYST, ADMIN_ANALYSIS, SUPERVISION_ANALYSIS ou ADMIN_SUPERVISION.';
