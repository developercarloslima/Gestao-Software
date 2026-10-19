# Base Universal NH v122 — Integração controlada da NH v83.139

## Fontes
- Base: NH Universal v121 (FIPE e importação segura).
- Referência das funcionalidades novas: NH operacional v83.139, comparada com v83.126.
- Gestão Cloud mantida: v107; agente VPS atualizado: v123.

## O que foi incorporado
- Telas e fluxo de dossiê, Termo de Responsabilidade, Termo de Ciência de Peças,
  WebAuthn e reaprovação de links pendentes após sete dias.
- API FIPE independente + pesquisa de versões por modelo/ano, CEP e datas de férias/feriados.
- Redistribuição de analistas pela Supervisão com validação de usuário ativo e auditoria.
- Regras regionais, preços por região e limites de terceiros editáveis pelo ADM;
  descontos apenas sobre mensalidade-base, taxas e adicionais integrais.
- PDF do termo de responsabilidade aceito anexo visualmente ao dossiê.
- Nome/CNPJ/logo por tenant; cláusulas do Termo de Peças imutáveis após preparação.
- Migrações SOMENTE na pasta db/tenant/V7–V11, sem importar registros ou preços da NH.
- Fluxo de importação segura, portais financeiros e integrações próprias da base mantidos.

## Restrições e pré-requisitos de produção
1. Não é a versão operacional da NH. Nunca sobrescrever o banco/volume da NH existente.
2. Publicação de novos clientes BLOQUEADA até homologação de Maven, Flyway,
   Prisma/NH Ponto, WebAuthn em HTTPS, políticas jurídicas e backups/restauração.
3. O agente VPS v123 requer imagem FIPE por digest e ativa o profile somente
   para Cotação/Vistoria. Não publica FIPE na internet.
4. Para liberar o Termo de Peças é obrigatório configurar as cláusulas próprias
   revisadas pelo jurídico do cliente (TENANT_PARTS_DELAY_CLAUSES).
5. O catálogo comercial de cada tenant começa VAZIO. ADM deve cadastrar
   planos/faixas/terceiros/coberturas e revisar parâmetros antes de operar.
6. Infraestrutura Render não é configurada nem publicada por este ZIP.

## Teste local Windows sem alterar bancos existentes
No PowerShell, dentro da pasta `nh-base-universal-v122`:

```powershell
powershell -ExecutionPolicy Bypass -File .\TESTAR_BASE_UNIVERSAL_V122_WINDOWS.ps1 -SomenteVerificar
powershell -ExecutionPolicy Bypass -File .\TESTAR_BASE_UNIVERSAL_V122_WINDOWS.ps1 -Executar
```

O comando `-Executar` constrói a imagem com `backend/Dockerfile.tenant`,
cria um PostgreSQL de teste em RAM e valida 11 migrações.
O ambiente usa o projeto Docker `gs-nh-v122-homologacao` e porta
`127.0.0.1:3188`, sem montar volumes existentes.

Diagnóstico:

```powershell
docker compose -p gs-nh-v122-homologacao -f docker-compose.tenant-homologacao.yml logs --tail=200 api-test
docker compose -p gs-nh-v122-homologacao -f docker-compose.tenant-homologacao.yml ps
```

Depois dos testes, pode encerrar os serviços de teste (sem `-v`):

```powershell
docker compose -p gs-nh-v122-homologacao -f docker-compose.tenant-homologacao.yml down
```

## Render — SOMENTE após aprovação local
- Criar serviços separados de homologação, PostgreSQL exclusivo, storage de
  evidências (persistente e com backups), backend imagem `Dockerfile.tenant`,
  web e FIPE internos. Não reutilizar banco de produção da NH.
- Configurar segredo por tenant, `SPRING_PROFILES_ACTIVE=tenant`, `GS_TENANT_MODE=true`,
  URLs HTTPS, domínio e contrato/catálogo próprios. Não expor `fipe-api` publicamente.
- Validar health, login, CPF, cotação, FIPE, vistoria, aceites WebAuthn, anexos,
  dossiê, importação criptografada, e restore de backup antes da publicação.
- NH Ponto depende da migração Prisma versionada, ainda não certificada.
- Revisar nomes e CNPJ, logos, política de retenção e termos legais de cada cliente.

## Testes incluídos
```
python ci/test_release_static.py
python ci/test_merge_v119.py
python ci/test_v122_integrations.py
node --test web/tests/*.test.cjs
python -m pytest api-fipe/tests -q
python ci/auditar_template.py --root . --tenant-runtime
```

A aprovação nesses testes ESTÁTICOS não confirma aplicação real em PostgreSQL.
