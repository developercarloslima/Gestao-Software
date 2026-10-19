-- Relaciona cotações e vistorias históricas ao cadastro atual do consultor,
-- independentemente de ele ter sido importado, cadastrado no portal ou voluntário.

UPDATE quotations q
SET consultant_id = c.id,
    consultant_name = c.name
FROM consultants c
WHERE q.consultant_id IS NULL
  AND lower(trim(regexp_replace(translate(upper(q.consultant_name),
        'ÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑ',
        'AAAAAEEEEIIIIOOOOOUUUUCN'), '[^A-Z0-9]+', ' ', 'g'))) = c.normalized_name;

-- Quando a vistoria já está ligada a uma cotação, a cotação é a fonte mais confiável
-- para recuperar o consultor responsável.
UPDATE inspection_requests i
SET consultant_id = q.consultant_id,
    consultant_name = q.consultant_name
FROM quotations q
WHERE i.quotation_id = q.id
  AND i.consultant_id IS NULL
  AND q.consultant_id IS NOT NULL;

-- Recupera também vistorias manuais ou históricas que não possuem cotação vinculada.
UPDATE inspection_requests i
SET consultant_id = c.id,
    consultant_name = c.name
FROM consultants c
WHERE i.consultant_id IS NULL
  AND lower(trim(regexp_replace(translate(upper(i.consultant_name),
        'ÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑ',
        'AAAAAEEEEIIIIOOOOOUUUUCN'), '[^A-Z0-9]+', ' ', 'g'))) = c.normalized_name;

CREATE INDEX IF NOT EXISTS idx_quotations_consultant_name_lower
    ON quotations(lower(consultant_name));

CREATE INDEX IF NOT EXISTS idx_inspection_requests_consultant_name_lower
    ON inspection_requests(lower(consultant_name));
