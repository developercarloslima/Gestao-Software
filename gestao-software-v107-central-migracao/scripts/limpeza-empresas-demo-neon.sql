-- USO MANUAL, APENAS NO BANCO CENTRAL DE DEMONSTRACAO DO GESTAO.
-- NAO e uma migracao Flyway e NAO roda no deploy.
-- A senha do Neon deve ser configurada no Console, nunca neste arquivo.
-- Antes de trocar ROLLBACK por COMMIT, crie um backup/branch recuperavel no Neon.
-- Limpa somente as duas empresas ficticias previamente cadastradas.
-- Mantem gs_users (Carlos/Pedro), catalogo, parceiros, VPS e preferencias.
-- O historico gs_cloud_audit e preservado, com company_id NULL pelo FK ON DELETE SET NULL.

BEGIN;
SET LOCAL lock_timeout = '5s';

-- IDs conferidos no painel SQL demonstrativo em 27/10/2026.
-- Se o banco foi recriado ou mais empresas foram cadastradas, a execucao para.
DO $$
DECLARE
  total_companies INTEGER;
  matched_companies INTEGER;
  has_real_payment BOOLEAN;
  has_running_deployment BOOLEAN;
BEGIN
  SELECT COUNT(*) INTO total_companies FROM platform_companies;
  SELECT COUNT(*) INTO matched_companies
    FROM platform_companies
   WHERE (id = '21328eff-b74c-434d-8f23-f52796257319'::uuid AND trade_name = 'Novo Horizonte')
      OR (id = '0bb45ccc-80ee-4e0f-b1ec-f825b04af795'::uuid AND trade_name = 'SMG');

  IF total_companies <> 2 OR matched_companies <> 2 THEN
    RAISE EXCEPTION 'LIMPEZA CANCELADA: esperado exatamente Novo Horizonte e SMG ficticias, com IDs conferidos. Total=%, correspondentes=%', total_companies, matched_companies;
  END IF;

  SELECT EXISTS (
    SELECT 1 FROM platform_company_invoices
     WHERE company_id IN (SELECT id FROM platform_companies)
       AND status = 'PAID'
  ) OR EXISTS (
    SELECT 1 FROM gs_verified_payment vp
    JOIN platform_company_invoices i ON i.id = vp.invoice_id
    WHERE i.company_id IN (SELECT id FROM platform_companies)
  ) INTO has_real_payment;
  IF has_real_payment THEN
    RAISE EXCEPTION 'LIMPEZA CANCELADA: existem pagamentos marcados como pagos ou verificados. Revisar antes de apagar.';
  END IF;

  SELECT EXISTS (
    SELECT 1 FROM gs_deployments
     WHERE company_id IN (SELECT id FROM platform_companies)
       AND status IN ('QUEUED', 'PROVISIONING', 'VERIFYING', 'ACTIVE')
  ) INTO has_running_deployment;
  IF has_running_deployment THEN
    RAISE EXCEPTION 'LIMPEZA CANCELADA: existe implantacao ativa ou pendente. Encerrar o fluxo antes.';
  END IF;
END $$;

-- gs_deployment_events e eliminado em cascata com seus jobs de demonstracao.
DELETE FROM gs_deployments
 WHERE company_id IN (SELECT id FROM platform_companies);

-- Nenhum pagamento verificado e permitido pelo bloqueio acima.
-- gs_invoice_checkout desaparece em cascata ao apagar as faturas.
DELETE FROM platform_company_invoices
 WHERE company_id IN (SELECT id FROM platform_companies);

-- Os demais vinculos ficticios (modulos, contratos, arquivos, hospedagem,
-- capacidade e DNS) sao apagados pelas FK ON DELETE CASCADE.
DELETE FROM platform_companies
 WHERE (id = '21328eff-b74c-434d-8f23-f52796257319'::uuid AND trade_name = 'Novo Horizonte')
    OR (id = '0bb45ccc-80ee-4e0f-b1ec-f825b04af795'::uuid AND trade_name = 'SMG');

-- Mostra a situacao APOS a limpeza, mas antes de confirmar a transacao.
SELECT COUNT(*) AS empresas_restantes FROM platform_companies;
SELECT username, active FROM gs_users WHERE username IN ('carlos.lima','pedro.henrique') ORDER BY username;

ROLLBACK; -- SIMULACAO SEGURA. Troque SOMENTE esta linha por COMMIT apos revisar e ter backup.
