ALTER TABLE quotations
    ALTER COLUMN plate DROP NOT NULL;

ALTER TABLE inspection_requests
    ALTER COLUMN plate DROP NOT NULL;

ALTER TABLE inspection_requests
    ADD COLUMN IF NOT EXISTS residence_address VARCHAR(600);

COMMENT ON COLUMN inspection_requests.residence_address IS
    'Endereço residencial informado pelo associado ao concluir uma nova vistoria.';
