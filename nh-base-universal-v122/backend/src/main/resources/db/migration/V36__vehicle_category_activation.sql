ALTER TABLE vehicle_categories
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX idx_vehicle_categories_active ON vehicle_categories(active);
