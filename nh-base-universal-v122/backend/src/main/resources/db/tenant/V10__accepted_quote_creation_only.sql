-- Evita revalidar cotação vencida ao gravar WebAuthn de vistoria EXISTENTE.
-- Exige cotação aceita e vigente ao criar ou trocar o vínculo comercial.
-- NH v83.138: preservar a exigencia comercial na CRIACAO da vistoria.
-- Cotacoes que vençam DEPOIS da vistoria nao podem impedir a gravacao de
-- aceite WebAuthn, selfie, dossie, termos ou atualizacao de auditoria.
-- Hibernate faz UPDATE de todas as colunas, mesmo quando request_type e
-- quotation_id permanecem iguais: por isso um UPDATE OF nao e suficiente.
CREATE OR REPLACE FUNCTION nh_enforce_new_inspection_accepted_quote()
RETURNS TRIGGER AS $$
DECLARE
    must_validate BOOLEAN := false;
BEGIN
    IF TG_OP = 'INSERT' THEN
        must_validate := true;
    ELSIF TG_OP = 'UPDATE' THEN
        must_validate := NEW.request_type IS DISTINCT FROM OLD.request_type
                         OR NEW.quotation_id IS DISTINCT FROM OLD.quotation_id;
    END IF;

    IF NEW.request_type = 'NEW_INSPECTION' AND must_validate THEN
        IF NEW.quotation_id IS NULL THEN
            RAISE EXCEPTION 'Nova vistoria exige cotação aceita vinculada.'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM quotations q
            WHERE q.id = NEW.quotation_id
              AND q.status = 'ACCEPTED'
              AND q.valid_until >= NOW()
        ) THEN
            RAISE EXCEPTION 'Nova vistoria exige cotação ACCEPTED e dentro do prazo de validade.'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_inspection_requires_accepted_quote ON inspection_requests;
CREATE TRIGGER trg_inspection_requires_accepted_quote
BEFORE INSERT OR UPDATE OF request_type, quotation_id
ON inspection_requests
FOR EACH ROW
EXECUTE FUNCTION nh_enforce_new_inspection_accepted_quote();


