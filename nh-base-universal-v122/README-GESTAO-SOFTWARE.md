# Novo Horizonte v91 — Cliente da Gestão Software

Este é o projeto operacional da **Novo Horizonte**. O Control Plane da Gestão Software não está embutido neste projeto.

## Separação
- Gestão Software: site e administração central próprios.
- Novo Horizonte: aplicação, banco e serviços próprios.
- A NH consulta a Gestão apenas para identidade, status da assinatura e módulos habilitados.

## Desenvolvimento local junto com a Gestão Software
1. Suba primeiro a Gestão Software em `http://localhost:3100`.
2. Depois suba este projeto em `http://localhost:3000`.
3. O backend NH usa por padrão:
   - `GESTAO_CONTROL_URL=http://host.docker.internal:3100`
   - `GESTAO_TENANT_HOST=nh.gestaosoftware.com.br`

No Windows com Docker Desktop, `host.docker.internal` permite o container da NH acessar o Control Plane rodando na porta 3100.

## Produção
```env
GESTAO_CONTROL_URL=https://gestaosoftware.com.br
GESTAO_TENANT_HOST=nh.gestaosoftware.com.br
GESTAO_CONTROL_CACHE_SECONDS=60
```

## Controle central
A Gestão Software pode alterar:
- logo
- nome exibido
- texto `Gestão Software LTDA`
- favicon
- cores
- slogan
- domínio/subdomínio
- módulos habilitados
- status da empresa

O frontend NH consome `/api/public/tenant-config`. O backend também usa a configuração para bloquear APIs dos módulos que não estejam contratados.

## Fallback
Se o Control Plane ficar temporariamente indisponível, a NH utiliza a última configuração em cache. Se a aplicação iniciar sem conseguir acessar a Gestão, usa a configuração local padrão da Novo Horizonte para preservar a operação.
