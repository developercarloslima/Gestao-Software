-- Dados FIPE de veículos migrados: nunca ativar cobrança ou substituir cotações.
ALTER TABLE commercial_vehicles ADD COLUMN IF NOT EXISTS zero_km BOOLEAN;
ALTER TABLE commercial_vehicles ADD COLUMN IF NOT EXISTS vehicle_type VARCHAR(40);
ALTER TABLE commercial_vehicles ADD COLUMN IF NOT EXISTS brand VARCHAR(140);
ALTER TABLE commercial_vehicles ADD COLUMN IF NOT EXISTS model_year INTEGER;
ALTER TABLE commercial_vehicles ADD COLUMN IF NOT EXISTS fipe_version VARCHAR(220);
ALTER TABLE commercial_vehicles ADD COLUMN IF NOT EXISTS fipe_code VARCHAR(30);
ALTER TABLE commercial_vehicles ADD COLUMN IF NOT EXISTS fipe_value NUMERIC(14,2);
