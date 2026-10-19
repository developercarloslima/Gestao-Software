-- Validade comercial da cotação e proteção da Nova Vistoria.
-- Novas cotações escolhem vencimentos de 30 a 40 dias à frente nos dias 5,10,15,20,25,30.
-- A seleção é validada na aplicação; este trigger garante que uma cotação vencida
-- nunca seja utilizada para criar uma NOVA VISTORIA, mesmo em chamada direta ao banco/API.

CREATE OR REPLACE FUNCTION nh_enforce_new_inspection_accepted_quote()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.request_type = 'NEW_INSPECTION' THEN
        IF NEW.quotation_id IS NULL THEN
            RAISE EXCEPTION 'Nova vistoria exige cotação aceita vinculada.'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM quotations q
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
