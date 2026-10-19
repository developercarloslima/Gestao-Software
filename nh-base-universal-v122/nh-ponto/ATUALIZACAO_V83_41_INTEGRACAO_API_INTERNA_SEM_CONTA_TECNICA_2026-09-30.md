# V83.41 — Integração API interna sem conta técnica

## Objetivo
Remover a conta técnica `admin-integracao-portal@apontamento.local` e manter a integração entre o painel principal e o Apontamento Digital exclusivamente por API interna autenticada por token.

## Alterações
- removido o endpoint `/internal/admin/session`;
- removida a criação/uso de sessão JWT baseada em usuário técnico;
- o seed exclui contas técnicas legadas `admin-integracao-portal@apontamento.local`;
- também elimina a cópia operacional antiga de `comercial@nhprotecao.com.br` dentro do tenant Novo Horizonte, preservando apenas o Super Adm global do Apontamento Digital;
- overview, selfies e revisão continuam disponíveis ao painel principal por `X-NH-Internal-Token`;
- o botão do painel principal passa apenas a abrir `/ponto/`; a gestão completa exige login normal com uma conta real do Apontamento Digital;
- `comercial@nhprotecao.com.br` continua sendo Super Adm apenas do Apontamento Digital.

## Segurança
A integração servidor-a-servidor não depende de usuário humano, senha, passkey ou sessão artificial. As ações sensíveis disparadas pelo painel principal continuam exigindo a autenticação/passkey do administrador do portal principal antes de o backend chamar a API interna.
