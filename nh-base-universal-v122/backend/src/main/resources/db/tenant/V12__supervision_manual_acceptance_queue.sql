-- A v122 possui Flyway isolado do tenant: migração aditiva, sem reaplicar V104 da VPS.
-- Movimentação operacional da fila; não altera accepted_at nem provas WebAuthn.
ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS acceptance_queue_manual_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS acceptance_queue_manual_by VARCHAR(160),
    ADD COLUMN IF NOT EXISTS acceptance_queue_manual_reason VARCHAR(1200);
COMMENT ON COLUMN inspection_requests.acceptance_queue_manual_at IS
    'Fila Aceites realizados movida manualmente. NÃO é aceite digital nem assinatura.';
