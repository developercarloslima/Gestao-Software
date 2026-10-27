# Validação do ambiente Gestão Software

Data: 22/10/2026. Base: `7efef97` (`main`).
Branch: `fix/gestao-development-environment`.

## Correções

- Compose local agora repassa as duas variáveis de bootstrap ao backend; exemplo
  de ambiente e documentação explicam o requisito, sem senhas padrão.
- Scripts `setup-dev.sh` e `start-dev.sh` preparam os builds/testes e iniciam
  PostgreSQL, backend e frontend locais. Estado e logs ficam em `.dev/`.
- O start força datasource **e Flyway** para `127.0.0.1:55432/gestao_dev`,
  verifica conflitos de portas e saúde HTTP e encerra os processos que iniciou.
  Provisionamento automático, orquestração e DNS permanecem desabilitados.
- Servidor HTTP de desenvolvimento entrega o build React, admin, contrato e
  arquivos compartilhados, encaminhando `/api` apenas ao backend loopback.
- Vite encaminha as rotas administrativas e de API ao servidor local.
- Smoke tests HTTP e navegador validam login real sem alterar senhas.

## Resultados executados

| Validação | Resultado |
| --- | --- |
| `scripts/setup-dev.sh` completo | Passou |
| Maven `verify`, Java 21 | 11 testes, zero falhas/erros/skips; JAR gerado |
| PostgreSQL 17.11 local, instalação limpa | 9 migrações V1–V9 aplicadas com sucesso |
| Reinício com banco local existente | Histórico v9 validado; hashes e flags de senha preservados |
| URLs externas fictícias herdadas em DATABASE_URL, SPRING_DATASOURCE_URL e SPRING_FLYWAY_URL | Backend e Flyway continuaram no PostgreSQL local |
| `pnpm install --frozen-lockfile` e build TypeScript/Vite | Passaram |
| `release_guard_test.py` | 7 verificações passaram |
| `cloud_ui_test.cjs` | Carga, capacidade, disponibilidade, reserva e payload passaram com API simulada |
| Java avulso: DNS / posicionamento | 16 / 11 cenários passaram |
| `real_browser_test.py` | Chromium em 390, 768 e 1366 px; API/DNS simulados; sem overflow/erro JS |
| `local_environment_test.py` | 17 requisições; site, assets, admin, saúde, autenticação e acesso negado passaram |
| `local_browser_test.py` | Carlos e Pedro: login real, sessão e diálogo de troca obrigatória |
| Vite na porta 3000 | Site, saúde API, login, contrato, compartilhados e logo retornaram HTTP 200 |
| Agente `gestao-cloud-agente-v123`: unittest discovery | 20 testes passaram, sem VPS/Docker reais |
| Sintaxe Bash e `git diff --check` | Passaram |

As variáveis configuradas foram utilizadas sem imprimir valores. No banco
**local**, `carlos.lima` e `pedro.henrique` estão ativos com troca de senha
obrigatória. Senhas inválidas e tokens inválidos foram rejeitados; rotas
administrativas protegidas retornaram 403 antes da troca. Nenhum teste submeteu
uma troca de senha, e os hashes permaneceram iguais após reiniciar.

## Ambiente e limites

Ferramentas ausentes foram extraídas em `/workspace/tools`, sem instalação no
sistema: Maven 3.9.9, JDK 21 completo e PostgreSQL 17.11. Para este workspace:

```bash
source /workspace/tools/activate-gestao.sh
cd /workspace/Gestao-Software/gestao-software-v107-central-migracao
scripts/start-dev.sh
```

O arquivo de ativação é específico deste workspace e não integra o commit.
Ele seleciona ferramentas, cache e proxy Maven; não contém senhas de usuários.
O PostgreSQL local usa `trust` em loopback e socket privado, adequado apenas
para este workspace de desenvolvimento isolado.

O status gerenciado informou as duas variáveis com estado `unknown`; a
validação funcional comprovou que os valores estavam disponíveis ao processo.
Docker Compose completo não foi executado: o socket Docker não está acessível
neste executor. A validação funcional utilizou processos nativos isolados.
Não foram executadas as suítes da aplicação separada `nh-base-universal-v122`.

## O que falta

- Revisar o diff e o PR e decidir a integração da branch.
- Se Docker for o fluxo desejado, executar Compose em um ambiente com daemon
  acessível; builds de containers e o proxy Nginx ainda não foram validados aqui.
- A troca inicial das senhas fica para os próprios usuários quando desejarem
  utilizar os módulos administrativos; os testes preservaram essa exigência.
- Integrações reais de VPS, pagamentos, DNS e implantação continuam fora desta
  validação local e devem permanecer desligadas até homologação específica.

Não houve conexão, alteração de dados, senhas ou configurações dos bancos
Render/Neon. Nenhuma migração foi editada e nenhum deploy de produção foi feito.
