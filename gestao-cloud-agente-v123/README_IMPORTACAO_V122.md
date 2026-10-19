# Agente VPS v122 — segredos da importação

Para cada novo tenant, o agente gera `IMPORT_AES_KEY_BASE64` aleatória de 32 bytes (Base64) **uma única vez** e a salva no `secrets.env` individual, protegido (0600), sem enviar a chave ao painel Gestão. O Docker Compose envia o valor somente para o backend daquela empresa.

**IMPORTANTE:** backups externos e criptografados do `secrets.env` são essenciais. Perder a chave impede recuperar os CSVs originais cifrados, mesmo que o banco PostgreSQL esteja íntegro. Não reinicie nem altere a chave após a importação.

Workspaces criados com versões anteriores não têm necessariamente essa variável: a importação fica inativa (HTTP 503) até o operador autorizado inserir uma chave individual estável. O agente não redefine os segredos existentes automaticamente ao retomar um deploy.

Os endpoints de importação exigem autenticação ADMIN dentro da empresa. O DNS, as imagens e o PostgreSQL ainda exigem homologação antes de promover a produção.
