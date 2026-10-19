-- V77: contexto de auditoria do aceite digital da vistoria/cotação.
-- Preserva o destino de WhatsApp usado no envio, um identificador técnico do
-- aparelho/navegador e o instante em que o GPS foi capturado para o aceite.

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS acceptance_whatsapp_sent_to VARCHAR(30),
    ADD COLUMN IF NOT EXISTS acceptance_device_id VARCHAR(96),
    ADD COLUMN IF NOT EXISTS acceptance_location_captured_at TIMESTAMPTZ;

COMMENT ON COLUMN inspection_requests.acceptance_whatsapp_sent_to IS
    'Número normalizado de WhatsApp usado pelo sistema quando o link de aceite aprovado foi enviado.';
COMMENT ON COLUMN inspection_requests.acceptance_device_id IS
    'Identificador técnico gerado pelo sistema para o navegador/aparelho que iniciou o aceite; não corresponde a IMEI/serial físico.';
COMMENT ON COLUMN inspection_requests.acceptance_location_captured_at IS
    'Instante informado pelo aparelho em que latitude/longitude do aceite foram capturadas.';
