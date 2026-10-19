ALTER TABLE portal_users
    ADD COLUMN IF NOT EXISTS face_enrolled_at TIMESTAMPTZ;

COMMENT ON COLUMN portal_users.face_enrolled_at IS
    'Data em que a referência facial do usuário do portal foi cadastrada na Face API. NULL = cadastro facial pendente.';
