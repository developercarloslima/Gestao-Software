-- V83.19: somente o ADMIN geral mantém passkey no login.
-- Credenciais dos demais perfis são removidas para que o acesso fique exclusivamente por usuário + senha.
UPDATE portal_users
SET passkey_credential_id = NULL,
    passkey_public_key = NULL,
    passkey_algorithm = NULL,
    passkey_sign_count = NULL,
    passkey_registration_challenge = NULL,
    passkey_registration_expires_at = NULL,
    passkey_assertion_challenge = NULL,
    passkey_assertion_expires_at = NULL,
    passkey_origin = NULL,
    passkey_rp_id = NULL,
    passkey_enrolled_at = NULL
WHERE role <> 'ADMIN';

-- Facial deixou de ser fator de login para todos os perfis do portal.
UPDATE portal_users SET face_enrolled_at = NULL WHERE face_enrolled_at IS NOT NULL;
