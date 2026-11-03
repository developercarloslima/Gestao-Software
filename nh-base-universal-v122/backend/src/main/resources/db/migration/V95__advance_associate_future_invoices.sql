-- Apenas veículos novos usam emissão antecipada. Os já assinados no Asaas
-- continuam com suas assinaturas recorrentes para evitar títulos duplicados.
ALTER TABLE commercial_vehicles
  ADD COLUMN IF NOT EXISTS advance_billing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS next_advance_billing_at DATE;
CREATE INDEX IF NOT EXISTS idx_commercial_vehicles_advance_billing
  ON commercial_vehicles(next_advance_billing_at)
  WHERE advance_billing_enabled=TRUE AND asaas_subscription_id IS NULL;
