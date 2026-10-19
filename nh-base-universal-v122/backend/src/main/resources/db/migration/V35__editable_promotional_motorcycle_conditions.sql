ALTER TABLE promotional_motorcycle_prices
    ADD COLUMN min_fipe NUMERIC(14,2) NOT NULL DEFAULT 0.00;

ALTER TABLE promotional_motorcycle_prices
    ADD CONSTRAINT chk_promotional_motorcycle_min_fipe CHECK (min_fipe >= 0),
    ADD CONSTRAINT chk_promotional_motorcycle_fipe_range CHECK (max_fipe IS NULL OR max_fipe >= min_fipe);
