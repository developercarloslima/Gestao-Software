-- Contas genéricas/operacionais não devem exigir troca de senha no primeiro acesso.
-- Contas pessoais criadas para integrantes específicos continuam com a regra de troca obrigatória.

UPDATE portal_users
SET must_change_password = FALSE,
    updated_at = NOW()
WHERE must_change_password = TRUE
  AND (
      created_by IN ('BOOTSTRAP', 'TOW_BOOTSTRAP', 'WORKSHOP_BOOTSTRAP')
      OR normalized_username IN ('consultor', 'analista', 'guincho', 'oficina', 'admin@nh.local')
  );
