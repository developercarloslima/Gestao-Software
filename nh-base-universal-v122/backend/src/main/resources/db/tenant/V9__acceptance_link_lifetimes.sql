-- 7 dias para os links após liberação/reaprovação. Sem atualização retroativa
-- de aceites de clientes existentes (banco universal nasce sem histórico NH).
ALTER TABLE inspection_requests ADD COLUMN IF NOT EXISTS acceptance_link_available_at TIMESTAMPTZ;
ALTER TABLE nh_event_records ADD COLUMN IF NOT EXISTS public_acceptance_issued_at TIMESTAMPTZ;
ALTER TABLE inspection_responsibility_terms ADD COLUMN IF NOT EXISTS link_available_at TIMESTAMPTZ;
ALTER TABLE inspection_parts_delay_terms ADD COLUMN IF NOT EXISTS link_available_at TIMESTAMPTZ;
COMMENT ON COLUMN inspection_requests.acceptance_link_available_at IS 'Primeira liberação de link de aceite ou renovação autorizada, não muda no simples reenvio.';
COMMENT ON COLUMN inspection_responsibility_terms.link_available_at IS 'Liberação do link atual do termo por perfil autorizado; nulo para prazo original.';
COMMENT ON COLUMN inspection_parts_delay_terms.link_available_at IS 'Liberação do link atual do termo por perfil autorizado; nulo para prazo original.';
