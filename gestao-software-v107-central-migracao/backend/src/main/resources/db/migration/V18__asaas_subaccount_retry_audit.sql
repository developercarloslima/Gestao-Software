-- Preserva a quantidade de tentativas e o status HTTP sem armazenar PII do provedor.
-- REVIEW_REQUIRED continua bloqueado contra reenvio após erro de resultado incerto.
ALTER TABLE platform_asaas_subaccounts
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS last_http_status INTEGER;

ALTER TABLE platform_asaas_subaccounts
    ADD CONSTRAINT ck_platform_asaas_subaccount_attempts CHECK (attempt_count > 0);
