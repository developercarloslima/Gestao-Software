-- V83.20: força novo cadastro da passkey dos administradores já existentes.
-- V72 criou a tabela de múltiplas passkeys; esta migration existe separadamente
-- para também cobrir instalações em que V72 já tenha sido aplicada antes desta versão.
DELETE FROM portal_admin_passkeys
 WHERE user_id IN (SELECT id FROM portal_users WHERE role = 'ADMIN');

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
       updated_at = NOW()
 WHERE role = 'ADMIN';
