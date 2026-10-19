-- V83.21: reset único de todas as credenciais/passkeys salvas no portal principal.
-- Não remove usuários, senhas, jornadas, fotos ou referências faciais.
DELETE FROM portal_admin_passkeys;

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
    passkey_enrolled_at = NULL,
    updated_at = NOW();
