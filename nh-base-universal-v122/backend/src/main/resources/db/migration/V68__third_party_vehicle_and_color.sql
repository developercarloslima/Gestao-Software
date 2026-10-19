ALTER TABLE nh_event_third_parties
    ADD COLUMN IF NOT EXISTS vehicle_name VARCHAR(180),
    ADD COLUMN IF NOT EXISTS vehicle_color VARCHAR(80);

-- Preserva compatibilidade visual dos registros antigos.
UPDATE nh_event_third_parties
   SET vehicle_name = vehicle_model
 WHERE vehicle_name IS NULL
   AND vehicle_model IS NOT NULL;
