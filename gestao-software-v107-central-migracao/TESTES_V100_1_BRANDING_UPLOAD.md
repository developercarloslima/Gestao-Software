# Gestão Software v100.1 — correção de branding NH e upload

## O que mudou
- `web/nginx.conf`: limite de upload aumentado para 30 MB no Nginx local. O Java já admite 25 MB por solicitação e o serviço de arquivos limita PDF a 20 MB e logo a 5 MB.
- `web/admin/index.html` / `app.js` / `styles.css`: seletor de cores + campo HEX, preset oficial NH (#070C40 / #F0F000 / #FFFFFF) e subdomínio de integração (ex.: `nh.gestaosoftware.com.br`).
- Salvar Empresa preserva o cadastro ao ocorrer falha no PDF e informa que **somente o anexo falhou**, permitindo reenviar pelo botão Editar empresa.

## Para atualizar localmente
Atualize os arquivos na mesma pasta existente do Compose da Gestão Software. Não troque a pasta do projeto se quiser usar o volume PostgreSQL existente (o Compose da Gestão não fixa `name`).
No PowerShell, dentro da pasta com `docker-compose.yml`:

```powershell
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 backend
```

## Para sincronizar cores com o NH
1. Abra `http://localhost:3100/admin/`, edite a empresa Novo Horizonte.
2. Clique em **Aplicar paleta oficial Novo Horizonte**, ou preencha os três HEX manualmente.
3. Informe `nh.gestaosoftware.com.br` em **Subdomínio de integração da empresa**.
4. Salve a empresa e selecione a logo (opcional) e o PDF (opcional).
5. Confira `http://localhost:3100/api/public/platform/branding?host=nh.gestaosoftware.com.br` (deve conter `companyId` real e as cores corretas).
6. Abra `http://localhost:3000/api/public/tenant-config`, confira `source: GESTAO_CONTROL` e pressione Ctrl+F5 no NH, aguardando cerca de 15 segundos após salvar.

**Atenção:** quando a configuração do NH usa `GESTAO_CONTROL_URL=http://host.docker.internal:3100`, o navegador deve buscar as imagens através da URL pública `GESTAO_CONTROL_PUBLIC_URL=http://localhost:3100`, configurada no novo `docker-compose.yml` do NH.

## Limitações verificadas
- Análise estática e sintaxe dos arquivos executadas. Não foi possível rodar os containers Docker neste ambiente.
- Não foi alterado nenhum banco de dados existente.
