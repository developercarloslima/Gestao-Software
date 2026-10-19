# Gestão Software v100 — Render

## Publicacao do codigo
1. Extraia este ZIP no Windows.
2. Instale `git` e GitHub CLI `gh`, se ainda não tiver.
3. Entre na pasta e execute PowerShell: `gh auth login` e `./PUBLICAR_GITHUB.ps1`.
4. No Render, selecione um workspace apropriado sem substituir serviços existentes.
5. Acesse https://dashboard.render.com/ > New > Blueprint > Connect `developercarloslima/gestao-software-control` > Branch main.
6. Blueprint path `render.yaml` para TESTES; `render.producao.yaml` só após aprovar cobrança.
7. Confira o plano e clique Deploy Blueprint.

## Resultado esperado (ainda nao implantado)
- Site/Admin: https://gestao-software-control-test-web.onrender.com
- API: https://gestao-software-control-test-api.onrender.com/api/health
- Admin: https://gestao-software-control-test-web.onrender.com/admin/login.html

## Segurança
- É um ambiente novo, sem os dados da VPS.
- Existem credenciais iniciais conhecidas nos scripts do sistema. TROQUE as senhas dos proprietários imediatamente após o primeiro login antes de conceder qualquer acesso.
- Evite publicar acesso administrativo sensível até revisar o provisionamento inicial de usuários.
- Nunca inclua arquivos .env privados no repositório.
- Produção exige Postgres pago e revisão de limites de memória/carga.
