-- Mantem intactas V1-V8. Neutraliza SOMENTE a senha inicial conhecida,
-- sem afetar senhas trocadas ou contas de outros usuarios.
UPDATE gs_users
SET password_hash = 'RENDER_BOOTSTRAP_REQUIRED_V9',
    active = FALSE,
    must_change_password = TRUE,
    updated_at = NOW()
WHERE username IN ('carlos.lima', 'pedro.henrique')
  AND password_hash = crypt('nh2027', password_hash);
