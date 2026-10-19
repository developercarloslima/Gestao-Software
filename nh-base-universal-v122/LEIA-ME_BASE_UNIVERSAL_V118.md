# Base universal de novos clientes — NH v118 (homologação)

## Objetivo
O código foi adaptado para instalar **uma empresa nova em banco próprio**, sem carregar planos, preços, comissões, colaboradores, logotipos, regulamentos ou contas de demonstração da Novo Horizonte.

**Não usar esta imagem contra a instalação antiga da NH.** O banco atual da NH usa a história Flyway `db/migration`; o novo template, *somente quando compilado com o perfil Maven `tenant`*, usa `db/tenant` e requer banco vazio/isolado.

## O que foi implementado nesta entrega
1. Migrações Flyway de nova empresa em `backend/src/main/resources/db/tenant/` (V1 apenas DDL extraída do esquema, V2 estado de checklist). Sem dados comerciais de outra empresa. **A execução SQL num PostgreSQL real permanece pendente.**
2. `backend/Dockerfile.tenant` + perfil Maven `-Ptenant` para não embutir as migrações históricas no JAR.
3. Bloqueio de banco reutilizado, demos e credenciais/domínios inadequados no modo de implantação.
4. Boot de NH Ponto sem `db push` e sem seed NH; requer baseline Prisma versionada antes de iniciar. Biometria e cadastro configurados com políticas conservadoras por padrão.
5. Portal `/admin/implantacao.html` com checklist e evidências HTTPS; opções e etapas ajustam-se aos módulos. Títulos são neutros e numerados progressivamente.
6. Endpoint `/api/health/tenant` retorna `readyForCustomers` para a Gestão liberar a URL somente depois de aprovação da implantação.
7. Troca dos PNGs e PDF de marca antiga por ícones/instruções genéricos; sem regulamento por padrão, devendo cada empresa carregar o seu.
8. Pipelines `.github/workflows/validacao-integracao.yml`, `publish-tenant-images.yml` e `gerar-baseline-prisma.yml`.

## Testar antes da produção
### 1. Auditoria sem criar servidores
```bash
python3 ci/auditar_template.py --root . --tenant-runtime
python3 ci/test_release_static.py
python3 -m pip install playwright
python3 -m playwright install chromium
python3 ci/test_ux.py
```

### 2. Baseline Prisma
Envie esta pasta para um repositório GitHub privado; em **Actions**, execute `Gerar migração inicial Prisma (revisão obrigatória)`. Baixe o artefato, **revise seu SQL**, incorpore `nh-ponto/prisma/migrations` ao repositório e execute novamente a CI. É proibido executar `prisma db push` como substituto em produção. O baseline só deve rodar em banco **vazio e separado**.

### 3. Maven, PostgreSQL e Prisma reais
Execute o workflow `Testar template universal`, com runner Linux e PostgreSQL 16 descartável, ou rode em Linux/macOS/WSL com Maven 3.9+, Java 21, Docker, Node 22 e PostgreSQL instalado:
```bash
bash ./VALIDAR_LOCAL_COMPLETO.sh
```
**Nenhum banco ou volume de produção deve ser usado nesses testes.** O script cria e destrói bancos de teste exclusivos. A compilação Maven e a migração em PostgreSQL **não foram executadas neste ambiente**.

### 4. Homologação de implantação ponta a ponta
- Confirmar Pay-Asaas Sandbox e o webhook idempotente, sem liberar apenas por status manual.
- DNS wildcard + HTTPS real + registro de imagens imutáveis por digest.
- Implantar em VPS sandbox autorizada, verificar Flyway, Prisma, UI, cadastros, cotação, relatórios, ponto, biometria e permissões por perfil.
- Executar testes de isolamento, recuperação de backup, volumes, latência, carga e LGPD (biometria e imagens).
- Aprovar o contrato/regulamento específico de cada cliente com o responsável jurídico.
- Entregar acessos iniciais por canal seguro, com troca de senha e sem registrar segredos em logs.

## Regras de liberação
- `TENANT_TEMPLATE_CERTIFIED=false` na Gestão até todos os testes reais passarem. Não basta alterar a flag.
- `ALLOW_RESOURCE_CREATION` deve ficar desativado no agente até a VPS, as imagens e o DNS estarem homologados.
- Cada tenant tem banco/volume/segredos exclusivos. É proibido apontar o template para o PostgreSQL existente da NH.
- O catálogo é inicialmente **vazio**. Cadastre planos, preços, descontos e coberturas daquela empresa. Não reutilize os preços NH.
- O formulário de implantação registra verificações humanas; um link de evidência não substitui um teste automático de backup, autenticação ou pagamento.

## Limitações atuais (não esconder de usuários)
- A V1 Flyway foi gerada de forma estrutural, ainda não executada de verdade num PostgreSQL 16 nesta sessão.
- Falta incorporar ao repositório a migration inicial oficial Prisma gerada no pipeline específico.
- Não foram realizados builds Maven/NH Ponto completos nem deploy real nesta sessão.
- AWS e Render exigem adaptadores novos; o agente atual atende **somente VPS gerenciada START**.
- O financeiro Asaas e a liberação de acessos devem ser homologados com provedor/ambiente reais.
- A revisão jurídica e comercial precisa ser feita para cada empresa.

Esse pacote é entregue como **candidato de homologação v118**, não uma certificação de produção.
