CREATE TABLE promotional_motorcycle_prices (
    id BIGSERIAL PRIMARY KEY,
    tier_code VARCHAR(40) NOT NULL UNIQUE,
    label VARCHAR(120) NOT NULL,
    min_cc INTEGER NOT NULL,
    max_cc INTEGER NOT NULL,
    max_fipe NUMERIC(14,2),
    monthly_price NUMERIC(14,2) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 100,
    CONSTRAINT chk_promotional_motorcycle_cc CHECK (min_cc > 0 AND max_cc >= min_cc),
    CONSTRAINT chk_promotional_motorcycle_max_fipe CHECK (max_fipe IS NULL OR max_fipe > 0),
    CONSTRAINT chk_promotional_motorcycle_monthly_price CHECK (monthly_price >= 0)
);

INSERT INTO promotional_motorcycle_prices
(tier_code, label, min_cc, max_cc, max_fipe, monthly_price, sort_order)
VALUES
('UP_TO_150_11K', 'Até 150cc até R$ 11 mil', 1, 150, 11000.00, 35.00, 10),
('CC_151_160', '151cc a 160cc', 151, 160, NULL, 45.00, 20),
('CC_161_300', '161cc a 300cc', 161, 300, NULL, 75.00, 30);
