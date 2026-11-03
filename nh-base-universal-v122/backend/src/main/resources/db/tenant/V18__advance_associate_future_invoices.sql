-- As novas cobrancas antecipadas usam colunas que precisam existir em cada tenant.
-- Preserva veiculos antigos (FALSE) e permite TRUE apenas nos cadastros novos.
-- V95 da base legada nao e executada em SPRING_PROFILES_ACTIVE=tenant.
ALTER TABLE commercial_vehicles
  ADD COLUMN IF NOT EXISTS advance_billing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS next_advance_billing_at DATE;

CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_advance_billing
  ON commercial_vehicles(next_advance_billing_at)
  WHERE advance_billing_enabled = TRUE AND asaas_subscription_id IS NULL;
