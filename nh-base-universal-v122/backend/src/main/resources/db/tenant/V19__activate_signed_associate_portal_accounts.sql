-- Recupera os logins de associados que já assinaram o contrato/dossiê,
-- mas ficaram inativos pela regra anterior que também exigia termos adicionais.
-- Não cria conta, não altera senha e não ativa usuários internos/manuais.
UPDATE portal_users AS portal
SET active = TRUE, updated_at = now()
WHERE portal.role = 'ASSOCIATE'
  AND portal.created_by = 'ASSOCIATE_AUTO_BOOTSTRAP'
  AND portal.active = FALSE
  AND portal.associate_id IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM inspection_requests AS inspection
      JOIN associates AS associate ON associate.id = portal.associate_id
      WHERE inspection.associate_id = portal.associate_id
        AND associate.active = TRUE
        AND regexp_replace(associate.document, '[^0-9]', '', 'g') = portal.normalized_username
        AND regexp_replace(inspection.cpf, '[^0-9]', '', 'g') = portal.normalized_username
        AND inspection.status = 'APPROVED'
        AND inspection.registration_completed_at IS NOT NULL
        AND inspection.accepted_at IS NOT NULL
        AND NOT (
            inspection.pending_contract_fipe_value IS NOT NULL
            AND inspection.pending_contract_monthly_value IS NOT NULL
        )
  );
