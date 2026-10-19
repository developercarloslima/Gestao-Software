-- Texto jurídico imutável por aceite: configuração de outro tenant nunca é reaproveitada.
ALTER TABLE inspection_parts_delay_terms ADD COLUMN IF NOT EXISTS legal_clauses_snapshot TEXT;
COMMENT ON COLUMN inspection_parts_delay_terms.legal_clauses_snapshot IS
    'Texto jurídico aprovado pela empresa e congelado quando o termo foi preparado; usado em todas as versões do PDF.';
