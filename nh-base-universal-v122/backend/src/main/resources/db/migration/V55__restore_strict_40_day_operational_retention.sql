-- V55: retenção operacional estrita de 40 dias.
--
-- Regra: a existência de arquivo impede APENAS o vencimento comercial da vistoria
-- (ex.: cotação de 7 dias). Ela não torna a vistoria ou seus arquivos permanentes.
-- Cotação, vistoria e arquivos continuam sujeitos à exclusão operacional após 40 dias,
-- independentemente de status (aceita, aprovada, em análise, expirada etc.).
--
-- V43 e V54 deixaram alguns arquivos com expires_at NULL. Normalizamos todos os
-- arquivos em PostgreSQL ainda preservados para a mesma data-limite da vistoria.
UPDATE inspection_assets ia
   SET expires_at = ir.created_at + INTERVAL '40 days'
  FROM inspection_requests ir
 WHERE ia.inspection_id = ir.id
   AND ia.storage_kind = 'DATABASE'
   AND ia.purged_at IS NULL
   AND ia.expires_at IS DISTINCT FROM (ir.created_at + INTERVAL '40 days');

COMMENT ON COLUMN inspection_assets.expires_at IS
    'Data-limite de retenção física. Arquivos da vistoria são removidos em até 40 dias, independentemente do status comercial.';
