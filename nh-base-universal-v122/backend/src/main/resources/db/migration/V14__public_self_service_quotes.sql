-- Permite que o cliente gere a própria cotação pelo site sem selecionar consultor.
ALTER TABLE quotations
    ADD COLUMN IF NOT EXISTS customer_cpf VARCHAR(11),
    ADD COLUMN IF NOT EXISTS origin VARCHAR(30) NOT NULL DEFAULT 'CONSULTANT';

ALTER TABLE quotations
    DROP CONSTRAINT IF EXISTS chk_quotation_origin;
ALTER TABLE quotations
    ADD CONSTRAINT chk_quotation_origin
        CHECK (origin IN ('CONSULTANT', 'SELF_SERVICE'));

-- Relaciona uma cotação aceita ao link público do Retrato NH criado automaticamente.
ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS quotation_id UUID;

ALTER TABLE inspection_requests
    DROP CONSTRAINT IF EXISTS inspection_requests_quotation_id_fkey;
ALTER TABLE inspection_requests
    ADD CONSTRAINT inspection_requests_quotation_id_fkey
        FOREIGN KEY (quotation_id) REFERENCES quotations(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_inspection_request_quotation
    ON inspection_requests(quotation_id)
    WHERE quotation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_quotations_origin_created
    ON quotations(origin, created_at DESC);
