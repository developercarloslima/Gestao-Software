-- Status intermediários e estorno: confirmação não significa saldo disponível.
ALTER TABLE platform_company_invoices DROP CONSTRAINT IF EXISTS ck_platform_invoice_status;
ALTER TABLE platform_company_invoices
  ADD CONSTRAINT ck_platform_invoice_status
  CHECK (status IN ('PENDING','CONFIRMED','PAID','PAID_IN_CASH','OVERDUE','REFUNDED','CANCELLED'));

-- Uma reentrega do mesmo evento Asaas não deve repetir efeitos financeiros.
CREATE TABLE IF NOT EXISTS gs_asaas_webhook_events (
  event_id VARCHAR(190) PRIMARY KEY,
  event_type VARCHAR(100) NOT NULL,
  payment_id VARCHAR(150) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
