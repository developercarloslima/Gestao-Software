# Correção de acesso administrativo local — 403 / credenciais

## Corrigido
- Token antigo armazenado no navegador agora é validado antes de redirecionar.
- Token inválido/403 é removido e o usuário retorna ao login.
- V3 redefine, uma única vez durante esta fase de testes, os dois administradores para a senha inicial `nh2027`.
- Ambos ficam `active=true` e `must_change_password=true`.

## Usuários
- `carlos.lima` / `nh2027`
- `pedro.henrique` / `nh2027`

## URL local
- `http://localhost:3100/admin/login.html`

Após entrar, é obrigatório definir uma nova senha.
