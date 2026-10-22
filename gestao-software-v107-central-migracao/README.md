# Gestão Software v99 — Cadastro comercial, contratos e identidade visual

Versão consolidada da plataforma Gestão Software baseada exatamente na v98 enviada pelo usuário.

## Novidades da v99
- Cadastro da empresa com forma de pagamento: Pix Automático, Débito Automático ou Boleto.
- Upload manual da logo da empresa-cliente (até 5 MB).
- Upload do PDF da identidade visual (até 20 MB).
- Arquivos persistidos no PostgreSQL, sem depender do disco temporário do container.
- Planos no cadastro exibidos somente pelo nome, sem preço embutido.
- Campo **Mensalidade calculada** editável para registrar o preço comercial final aprovado.
- Contrato criado automaticamente ao cadastrar uma empresa.
- Contrato com assinatura da empresa-cliente por link e assinatura interna exclusiva de Carlos Lima ou Pedro Henrique.
- Edição completa de empresa, plano, prazo, mensalidade, forma de pagamento, módulos e branding.
- Ações na lista de empresas: Ver empresa, Editar, Desativar e Excluir.
- Desativar preserva histórico e bloqueia a disponibilização dos módulos; Excluir remove os dados da empresa mediante confirmação.
- Site público com os planos: Start, Business, Pro, Scale, Enterprise, Dedicated Cloud e Hybrid Enterprise, sem preços públicos.
- Nova migration: `V5__company_files_payment_contracts.sql`.

## URLs locais
- Site: `http://localhost:3100/`
- Área administrativa: `http://localhost:3100/admin/login.html`
- Assinatura pública de contrato: `http://localhost:3100/contract/?token=...`

# Gestão Software v98 — site principal integrado

Versão da plataforma Gestão Software com a landing page institucional servida como site principal na rota `/`.

- Site público: `web/site-src`, compilado pelo container `web` para `/`
- Área Administrativa: `/admin/login.html`
- Control Plane, backend, banco, planos, módulos, calculadora e infraestrutura preservados da v97
- Tema claro/escuro no site público
- Planos públicos Bronze, Silver, Gold e Enterprise
- Logo oficial local da Gestão Software
- Imagens do hero, white-label e infraestrutura incluídas localmente no build; sem dependência de `/manus-storage`
- O Nginx continua preservando `/admin/` e `/api/`; o link de área administrativa abre o login real


## Histórico / instruções anteriores

# Gestão Software v97

Esta é a versão consolidada da Gestão Software com o site público atualizado a partir de `gestao2026(1).zip` e o Control Plane preservado em `/admin/`.

- Site público: `http://localhost:3100/`
- Área administrativa: `http://localhost:3100/admin/login.html`
- Backend/API: `/api/`

O botão **Área administrativa** do site público leva ao login real do Control Plane.

# Gestão Software v91 — Projeto independente

Este projeto é o **site institucional + Control Plane** da Gestão Software. Ele não é o sistema operacional da Novo Horizonte.

## URLs locais
- Site público: `http://localhost:3100`
- Área Administrativa: `http://localhost:3100/admin/`
- API: `http://localhost:3100/api`

## Usuários iniciais
- `carlos.lima` / `nh2027`
- `pedro.henrique` / `nh2027`

No primeiro acesso a troca de senha é obrigatória.

## Rodar localmente (PowerShell)
```powershell
Copy-Item .env.example .env
# edite AUTH_TOKEN_SECRET e POSTGRES_PASSWORD se desejar
docker compose up -d --build
docker compose ps
docker compose logs -f backend
```

## O que a Gestão controla
- Empresas-clientes
- Grupo empresarial
- Plano e contrato
- Módulos contratados
- Identidade visual white-label
- Domínio/subdomínio
- VPS / programa de hospedagem
- Mensalidades e inadimplência
- Despesas e receita da Gestão Software
- Divisão societária Pedro/Carlos
- Calculadora de planos

## Empresas iniciais
### Novo Horizonte — Grupo Sampaio
Módulos: Site Principal, Cota, Vistoria/Eventos, Gestão Financeira/Comercial e Apontamento Digital.

### SMG — Grupo Sampaio
Módulo: somente Apontamento Digital.

## Endpoint consumido pelos projetos-clientes
`GET /api/public/platform/branding?host=nh.gestaosoftware.com.br`

O retorno inclui branding, status e `enabledModules`.

## Correção de build 2026-10-05
Foi corrigida a inferência genérica de `Map.of(...)` em `PlatformUserService.authenticate`, que impedia a compilação Java 21 durante `mvn package`.
Para garantir que o Docker não reutilize a camada anterior, prefira `docker compose build --no-cache backend` no primeiro build desta correção.

## v96 — Site principal Gestão Software 2026

O site institucional enviado em `gestao2026.zip` é agora a página pública principal da Gestão Software.

- Site público: `http://localhost:3100/`
- Área Administrativa: `http://localhost:3100/admin/login.html`
- Control Plane: `http://localhost:3100/admin/`
- API: `http://localhost:3100/api/`

O container `web` compila automaticamente o React/Vite usando Node 22 e publica o `dist` no Nginx. A Área Administrativa da v95 é copiada separadamente para `/admin/`.
