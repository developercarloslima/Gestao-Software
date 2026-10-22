# Desenvolvimento local do Control Plane

Este diretório é a aplicação Gestão Software que autentica `carlos.lima` e
`pedro.henrique`. A pasta `nh-base-universal-v122` contém outra aplicação.

Requisitos: JDK 21 completo (`java` e `javac`), Maven 3.9+, Node 20+,
pnpm 11.25, Python 3 e PostgreSQL 16+ (`initdb`, `pg_ctl`, `psql` no PATH).
Use as variáveis `GESTAO_BOOTSTRAP_CARLOS_PASSWORD` e
`GESTAO_BOOTSTRAP_PEDRO_PASSWORD` já configuradas, com no mínimo 8 caracteres.
Não grave os valores no Git, na linha de comando ou em logs.

```bash
scripts/setup-dev.sh
scripts/start-dev.sh
# Em outro terminal, com as mesmas variáveis:
python3 tests/cloud/local_environment_test.py
python3 tests/cloud/local_browser_test.py
python3 tests/cloud/real_browser_test.py
```

Os testes de navegador precisam de Playwright para Python e Chromium
(`/usr/bin/chromium`; o teste local aceita `CHROMIUM_PATH`).
O script de setup executa os testes Java, o build React/TypeScript,
as verificações estáticas e os testes de interface sem navegador.

Site/admin: `http://127.0.0.1:3100`; API: `http://127.0.0.1:8080`.
O PostgreSQL exclusivo escuta em `127.0.0.1:55432`, banco `gestao_dev`.
Dados, logs e chave de token local ficam em `.dev/`, ignorado pelo Git.
A autenticação PostgreSQL local usa `trust`, limitada ao loopback e ao socket
privado do workspace; este script é apenas para desenvolvimento isolado.
Ctrl+C encerra os processos iniciados pelo script e preserva os dados locais.
Portas ocupadas interrompem a inicialização, sem encerrar outros serviços.

O script não carrega `.env` e força datasource e Flyway para o banco local,
mesmo com `DATABASE_URL`/`SPRING_DATASOURCE_URL` externos herdados.
Provisionamento automático, orquestração e DNS ficam desativados.
As migrações criam somente os dados locais. Usuários já inicializados mantêm
suas senhas: as variáveis de bootstrap não redefinem contas existentes.
O primeiro acesso exige troca de senha; os smoke tests apenas verificam esse
bloqueio e **não** fazem a troca.

Para editar React com recarga automática, mantenha `start-dev.sh` ativo e rode
`pnpm --dir web/site-src dev`. O Vite em `http://localhost:3000` encaminha
`/api`, `/admin`, `/contract`, `/shared` e `/assets` ao servidor local em 3100.

Alternativa Docker local: configure as duas variáveis de bootstrap no ambiente
ou em `.env` antes de `docker compose up -d --build`. O Compose agora repassa
essas variáveis ao backend. Não execute os arquivos Compose de produção para
validar desenvolvimento. Veja [o relatório de validação](DESENVOLVIMENTO_VALIDACAO.md).

---

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
- `carlos.lima`: variável `GESTAO_BOOTSTRAP_CARLOS_PASSWORD`
- `pedro.henrique`: variável `GESTAO_BOOTSTRAP_PEDRO_PASSWORD`

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
