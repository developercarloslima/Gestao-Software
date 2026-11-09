-- O aceite não equivale à quitação da adesão. Mantém contratos históricos intactos.
ALTER TABLE commercial_vehicles DROP CONSTRAINT IF EXISTS ck_commercial_vehicle_status;
ALTER TABLE commercial_vehicles ADD CONSTRAINT ck_commercial_vehicle_status
    CHECK (status IN ('DRAFT', 'PENDING_PAYMENT', 'ACTIVE', 'CANCELLED'));

ALTER TABLE financial_charges ADD COLUMN IF NOT EXISTS payment_method VARCHAR(20);
ALTER TABLE financial_charges ADD COLUMN IF NOT EXISTS issued_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_financial_adhesion_paid_bi
    ON financial_charges(paid_at, consultant_id, payment_method, vehicle_id)
    WHERE charge_type = 'ADHESION' AND status = 'RECEIVED';
CREATE INDEX IF NOT EXISTS ix_financial_boleto_issued_bi
    ON financial_charges(issued_at, consultant_id)
    WHERE billing_type = 'BOLETO' AND provider_payment_id IS NOT NULL;
