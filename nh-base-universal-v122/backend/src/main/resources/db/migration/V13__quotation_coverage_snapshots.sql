-- Preserva as coberturas exatamente como estavam no momento da emissão.
-- Assim, editar ou excluir um plano não modifica nem impede a geração de PDFs antigos.
CREATE TABLE quotation_coverage_snapshots (
    id BIGSERIAL PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    coverage_code VARCHAR(80) NOT NULL,
    coverage_name VARCHAR(180) NOT NULL,
    coverage_status VARCHAR(20) NOT NULL,
    detail VARCHAR(240),
    monthly_price NUMERIC(14,2),
    sort_order INTEGER NOT NULL,
    CONSTRAINT uk_quote_coverage_snapshot UNIQUE (quotation_id, coverage_code)
);

CREATE INDEX idx_quote_coverage_snapshot_order
    ON quotation_coverage_snapshots(quotation_id, sort_order, id);

-- Cotações já existentes recebem uma cópia do catálogo atual correspondente.
INSERT INTO quotation_coverage_snapshots(
    quotation_id,
    coverage_code,
    coverage_name,
    coverage_status,
    detail,
    monthly_price,
    sort_order
)
SELECT
    q.id,
    c.code,
    c.name,
    pc.status,
    pc.detail,
    pc.monthly_price,
    pc.sort_order
FROM quotations q
JOIN plans p ON p.code = q.selected_plan_code
JOIN plan_coverages pc ON pc.plan_id = p.id
JOIN coverages c ON c.id = pc.coverage_id
ON CONFLICT (quotation_id, coverage_code) DO NOTHING;
