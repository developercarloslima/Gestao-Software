-- Tabela FIPE para scooters/motos elétricas e proteção definitiva do fluxo de nova vistoria.
-- Nova vistoria: somente com quotation_id de uma cotação ACEITA.

-- Scooters e motos elétricas seguem a tabela padrão Nordeste de moto completa,
-- com a regra comercial específica: FIPE até R$ 11.000,00 = R$ 35,00.
DELETE FROM price_ranges
WHERE plan_id = (SELECT id FROM plans WHERE code = 'SCOOTER_ELECTRIC_STANDARD');

INSERT INTO price_ranges (plan_id, min_value, max_value, monthly_price) VALUES
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 0.00, 11000.00, 35.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 11000.01, 13000.00, 45.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 13000.01, 15000.00, 50.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 15000.01, 17000.00, 60.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 17000.01, 20000.00, 70.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 20000.01, 25000.00, 90.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 25000.01, 30000.00, 100.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 30000.01, 35000.00, 130.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 35000.01, 40000.00, 160.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 40000.01, 45000.00, 190.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 45000.01, 50000.00, 220.00),
((SELECT id FROM plans WHERE code='SCOOTER_ELECTRIC_STANDARD'), 50000.01, 55000.00, 250.00);

-- Defesa no próprio PostgreSQL. Mesmo uma chamada manual à API ou um bug no
-- frontend não consegue inserir/alterar NEW_INSPECTION sem cotação aceita.
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
        ) THEN
            RAISE EXCEPTION 'Nova vistoria só pode ser criada para cotação com status ACCEPTED.'
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
