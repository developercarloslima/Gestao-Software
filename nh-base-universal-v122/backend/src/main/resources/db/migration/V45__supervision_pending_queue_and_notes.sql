-- Supervisão acompanha toda a fila dos analistas e pode registrar O.B.S. própria.

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS supervision_note VARCHAR(1200),
    ADD COLUMN IF NOT EXISTS supervision_note_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supervision_note_by_name VARCHAR(160);

-- Garante o analista responsável também nas vistorias históricas ainda em fluxo,
-- usando o vínculo atual do consultor apenas quando a vistoria não possuía responsável salvo.
UPDATE inspection_requests ir
   SET assigned_analyst_id = analyst.id,
       assigned_analyst_name = analyst.name
  FROM consultants consultant
  JOIN consultants analyst ON analyst.id = consultant.assigned_analyst_id
 WHERE ir.consultant_id = consultant.id
   AND ir.assigned_analyst_id IS NULL;

-- Se o ID do analista já existia, mas o nome histórico ficou vazio, recupera o nome correspondente ao próprio ID.
UPDATE inspection_requests ir
   SET assigned_analyst_name = analyst.name
  FROM consultants analyst
 WHERE ir.assigned_analyst_id = analyst.id
   AND (ir.assigned_analyst_name IS NULL OR btrim(ir.assigned_analyst_name) = '');

COMMENT ON COLUMN inspection_requests.supervision_note IS
    'O.B.S. registrada pela Supervisão e exibida ao analista responsável ao abrir a vistoria.';
COMMENT ON COLUMN inspection_requests.supervision_note_updated_at IS
    'Data da última alteração da O.B.S. da Supervisão.';
COMMENT ON COLUMN inspection_requests.supervision_note_by_name IS
    'Nome do supervisor que realizou a última alteração da O.B.S. da Supervisão.';

COMMENT ON COLUMN consultants.assigned_analyst_id IS
    'Analista responsável pelo consultor. Um analista pode receber no máximo 30 consultores, validado pela aplicação.';
