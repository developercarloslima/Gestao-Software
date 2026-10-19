-- Corrige o acesso inicial do Control Plane em instalações criadas durante os testes locais.
-- Esta migration roda uma única vez. Após o primeiro login, a aplicação obriga a troca da senha.
INSERT INTO gs_users(username, display_name, password_hash, must_change_password, active, created_at, updated_at)
VALUES
  ('carlos.lima', 'Carlos Lima', crypt('nh2027', gen_salt('bf', 12)), TRUE, TRUE, NOW(), NOW()),
  ('pedro.henrique', 'Pedro Henrique', crypt('nh2027', gen_salt('bf', 12)), TRUE, TRUE, NOW(), NOW())
ON CONFLICT (username) DO UPDATE SET
  display_name = EXCLUDED.display_name,
  password_hash = EXCLUDED.password_hash,
  must_change_password = TRUE,
  active = TRUE,
  updated_at = NOW();
