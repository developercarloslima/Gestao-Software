ALTER TABLE consultants
    ADD COLUMN IF NOT EXISTS whatsapp VARCHAR(30);

COMMENT ON COLUMN consultants.whatsapp IS
    'WhatsApp do consultor usado como destino de retorno nos links públicos de comparação de planos.';
