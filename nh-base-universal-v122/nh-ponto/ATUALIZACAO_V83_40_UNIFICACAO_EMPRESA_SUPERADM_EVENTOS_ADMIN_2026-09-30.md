# V83.40 — Unificação da Novo Horizonte, Super Adm do Apontamento e criação de eventos pelo Admin

## Empresa Novo Horizonte
- `Novo Horizonte` e `Novo Horizonte Proteção Veicular` passam a ser tratados como a mesma empresa.
- Nome operacional canônico: `Novo Horizonte`.
- Razão social: `Novo Horizonte Proteção Veicular`.
- CNPJ/documento canônico do tenant do Apontamento Digital: `00000000000191`.
- O seed detecta tenants duplicados com esses nomes, consolida os vínculos na empresa canônica e remove o tenant duplicado.
- O painel do Super Adm não permite criar novamente uma segunda empresa usando um desses dois nomes.

## Super Adm do Apontamento Digital
- Username: `comercial@nhprotecao.com.br`.
- Senha fixa: `@Aguias2025`.
- Continua com o perfil `SUPER_ADMIN` e sem jornada.
- As contas legadas `Super Adm` / `SuperAdm` são migradas para o novo username.
- Esta conta existe somente no banco/autenticação do Apontamento Digital.
- O acesso integrado vindo do painel principal da Cota/Vistoria usa a conta técnica interna `admin-integracao-portal@apontamento.local`, com perfil `ADMIN`, evitando transformar o administrador principal do projeto em Super Adm do ponto.

## Eventos no painel principal
- A aba `Eventos` do Administrador passa a ter o botão `+ Criar evento`.
- O botão abre a Central de Eventos já autenticada e abre automaticamente o formulário completo de novo evento.
- O backend já permitia criação por `ADMIN`; a V83.40 expõe o fluxo na interface administrativa sem duplicar a lógica do formulário.
