alter table portal_users add column if not exists passkey_credential_id varchar(1024);
alter table portal_users add column if not exists passkey_public_key bytea;
alter table portal_users add column if not exists passkey_algorithm integer;
alter table portal_users add column if not exists passkey_sign_count bigint;
alter table portal_users add column if not exists passkey_registration_challenge varchar(160);
alter table portal_users add column if not exists passkey_registration_expires_at timestamptz;
alter table portal_users add column if not exists passkey_assertion_challenge varchar(160);
alter table portal_users add column if not exists passkey_assertion_expires_at timestamptz;
alter table portal_users add column if not exists passkey_origin varchar(320);
alter table portal_users add column if not exists passkey_rp_id varchar(253);
alter table portal_users add column if not exists passkey_enrolled_at timestamptz;

update portal_users set passkey_sign_count = 0 where passkey_sign_count is null and passkey_credential_id is not null;
