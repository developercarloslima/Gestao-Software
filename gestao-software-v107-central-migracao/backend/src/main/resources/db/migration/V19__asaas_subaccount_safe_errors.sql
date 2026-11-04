-- Diagnóstico Asaas: apenas código e descrição saneada, nunca o corpo bruto nem dados pessoais.
-- Conserva dados e status de tentativas anteriores.
ALTER TABLE platform_asaas_subaccounts
    ADD COLUMN IF NOT EXISTS last_error_code VARCHAR(60),
    ADD COLUMN IF NOT EXISTS last_error_message VARCHAR(300);
