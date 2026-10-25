# Implantar NH Universal v122 como demonstracao gratuita (Render + Neon)

## Escopo e isolamento

Este roteiro e **somente** para demonstracao; nao modifica a NH real da VPS nem o
banco central `gestao_demo_migracao`. O agente VPS, criacao automatica de instancias,
Asaas real, Ponto e acesso a dados de clientes permanecem **desativados**.

Arquivos envolvidos:
- `render.neon-demo.yaml`: cria duas novas aplicacoes Render Free, sem novo Render Postgres.
- `web/Dockerfile.render-public` e `web/nginx.render-public.conf.template`:
  servem a UI e encaminham `/api/` para a URL publica HTTPS da API.
- `render.yaml` original da NH e o Blueprint do Gestao permanecem intactos.

## Passo 1 — Banco vazio e independente no Neon

1. Crie preferencialmente um **novo projeto Neon** chamado `nh-universal-demo`
   (alternativamente, um banco vazio `nh_demo_tenant` no projeto atual).
2. Use um PostgreSQL **vazio** denominado `nh_demo_tenant`. Nao restaure o backup
   do Render/Gestao ou os dados reais da NH neste banco.
3. No console Neon, obtenha a string de conexao **direta** (sem `-pooler`).
   No Render ela sera informada como:
   `DATABASE_URL=jdbc:postgresql://HOST_NEON:5432/nh_demo_tenant?sslmode=require`
   mais `DATABASE_USERNAME` e `DATABASE_PASSWORD`, separados.
4. Guarde os dados de acesso somente no Render/Neon. Nunca os coloque no GitHub.

O processo Spring Boot em perfil `tenant` aplica exclusivamente as migracoes
`db/tenant/V1..V11`. A protecao `TenantIsolatedDatabaseGuard` recusa um banco
com tabelas nao pertencentes ao template universal.

## Passo 2 — Empresa ficticia no Gestao

1. No Gestao, cadastre ou selecione uma **empresa de teste**, sem dados pessoais reais.
2. Habilite o modulo comercial necessario para a demonstracao (ex.: Cota/Vistoria).
3. Copie o UUID exato da empresa; ele sera o `GESTAO_COMPANY_ID` da NH.
   Se precisar conferir no Neon central via SQL **somente leitura**:

   ```sql
   SELECT id, trade_name, status
   FROM public.platform_companies
   ORDER BY trade_name;
   ```

4. Quando o subdominio NH existir, vincule-o a essa mesma empresa no Gestao.
   A rota `/api/public/platform/branding?host=...` do Gestao deve reconhecer
   a empresa e seus modulos. Sem esse vinculo a NH bloqueia os modulos por seguranca.

## Passo 3 — Dois Web Services Render Free

1. Depois de mesclar o PR correspondente, entre no Render Dashboard > New > Blueprint.
2. Vincule `developercarloslima/Gestao-Software`, branch `main`.
3. Informe o **caminho personalizado** do Blueprint:
   `nh-base-universal-v122/render.neon-demo.yaml`.
4. **Nao selecione nem sincronize** os Blueprints anteriores do Gestao ou NH.
5. O novo Blueprint define apenas:
   - `nh122-demo-api`: Java/Tenant, Web Service Free.
   - `nh122-demo-web`: Nginx, Web Service Free.
6. O Render pode acrescentar um sufixo aos subdominios; copie as **URLs reais**
   exibidas pelo Dashboard, em vez de presumir o endereco.

### Variaveis obrigatorias da API NH

| Chave | Valor ou orientacao |
| --- | --- |
| `DATABASE_URL` | JDBC da base **nova** `nh_demo_tenant` (SSL obrigatorio) |
| `DATABASE_USERNAME` | Usuario do banco NH |
| `DATABASE_PASSWORD` | Senha do banco NH |
| `GESTAO_COMPANY_ID` | UUID da empresa **ficticia** no Gestao |
| `GESTAO_TENANT_HOST` | Hostname publico real da NH web, **sem** `https://` |
| `GESTAO_CONTROL_URL` | URL HTTPS publica da API do Gestao (conferir no Dashboard) |
| `GESTAO_CONTROL_PUBLIC_URL` | `https://gs107-hml-web-rewg.onrender.com` |
| `PUBLIC_WEB_URL` | URL HTTPS real da NH web |
| `PUBLIC_API_URL` | Mesma URL HTTPS da NH web, pois a web faz proxy de `/api/` |
| `ALLOWED_ORIGINS` | Mesma URL HTTPS da NH web |
| `ADMIN_USERNAME` | E-mail de login inicial para **essa** empresa |
| `ADMIN_PASSWORD` | Senha inicial com **16 ou mais caracteres** |
| `IMPORT_AES_KEY_BASE64` | Nova chave aleatoria de 32 bytes codificada em Base64 |

Os demais valores de seguranca sao gerados automaticamente ou definidos no Blueprint.
Nao ative a excecao de senha compartilhada `gestao1234` no dominio novo:
a regra de codigo da NH aceita essa excecao apenas no hostname antigo literal.

No Windows PowerShell, voce pode gerar uma chave de importacao por:
```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$rng.Dispose()
[Convert]::ToBase64String($bytes)
```
Cole **somente o resultado** em `IMPORT_AES_KEY_BASE64` no Render.

### Variaveis da NH web

| Chave | Valor |
| --- | --- |
| `BACKEND_HOST` | Hostname publico real da **API NH**, sem `https://` e sem barra |
| `BACKEND_API_URL` | Manter vazio; a UI chama `/api/` na mesma origem |

Se o deploy inicial falhar enquanto ainda nao existem URLs definitivas, corrija
essas variaveis no Dashboard e use **Save and deploy**. Os checks de inicio
validam que `GESTAO_TENANT_HOST` e `PUBLIC_WEB_URL` sejam correspondentes.

## Passo 4 — Testes antes de mostrar ao cliente

1. Acesse `https://DOMINIO_API_NH/api/health` e confirme `status: UP`.
2. Acesse `https://DOMINIO_WEB_NH/health` e confirme `ok`.
3. Acesse `https://DOMINIO_WEB_NH/api/health/tenant` e confirme
   `status: UP`. `readyForCustomers: false` e **esperado** ate terminar
   o checklist de ativacao; nao altere manualmente essa flag.
4. Confira `https://DOMINIO_WEB_NH/api/public/tenant-config`: deve
   apontar a empresa de demonstracao e refletir seus modulos. Se vier
   `UNCONFIGURED_FAIL_CLOSED`, corrija dominio/vinculo no Gestao.
5. Entre com a conta administrativa **desse tenant**, altere a senha quando
   solicitado e verifique as abas Cota, Analise, Supervisao, Consultor e Associado.
6. Cadastre apenas **planos, regras e registros ficticios**. O tenant nao herda
   catalogo ou historico comercial da NH real.
7. Valide persistencia reiniciando **somente o servico NH de teste** e
   conferindo os registros do banco Neon NH.
8. Mantenha desligados Asaas, biometria, agente VPS e importacao de dados reais.

## Limites importantes do Render Free

- Cada servico Free pode dormir apos periodo de inatividade; o primeiro acesso
  pode demorar para acordar.
- Render Free **nao recebe conexoes da rede privada** vindas de outro web
  service Free. Por isso o proxy desse laboratorio usa o hostname publico
  HTTPS da API.
- O armazenamento de arquivos **local ao container e efemero**. Fotos,
  videos e PDFs enviados podem desaparecer apos reinicio/redeploy/hibernacao.
  Nao use a demo gratuita para persistir documentos reais.
- A conta possui cota mensal agregada de horas de instancias Free. Verifique
  consumo antes de manter todos os servicos simultaneamente ativos.
- Banco Render Free expira: por isso este Blueprint **nao cria banco Render**.

## O que este roteiro nao faz

Nao faz deploy automatico, nao ativa tenant para clientes reais, nao paga
servicos, nao troca bancos do Gestao, nao toca na VPS NH e nao provisiona
tenants em massa. A implantacao do agente VPS v123 fica para o ambiente oficial.
