-- Aplicar a empresas já existentes: logins individuais que foram criados
-- automaticamente ao incluir um nome na Área do Colaborador.
--
-- Apenas contas CONSULTANT_AUTO_BOOTSTRAP ligadas a nomes CREATED_IN_PORTAL
-- e geradas no mesmo cadastro são elegíveis. Esse intervalo evita desativar
-- uma conta que o ADM tenha criado deliberadamente para um nome já existente.
-- Não remover colaboradores, cotações, vistorias, histórico ou credenciais.
-- A desativação é reversível pelo ADM e invalida tokens ativos imediatamente.
UPDATE portal_users AS account
SET active = FALSE,
    updated_at = NOW()
FROM consultants AS collaborator
WHERE account.consultant_id = collaborator.id
  AND account.role = 'CONSULTANT'
  AND account.created_by = 'CONSULTANT_AUTO_BOOTSTRAP'
  AND collaborator.role = 'CONSULTANT'
  AND collaborator.source = 'CREATED_IN_PORTAL'
  AND account.created_at <= collaborator.created_at + INTERVAL '2 minutes'
  AND account.active = TRUE;
