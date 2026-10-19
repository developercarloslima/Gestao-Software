# Gestão Software v107 — central de acesso às migrações

A aba **Importar dados** permite selecionar qualquer empresa cadastrada e abrir seu painel de importação seguro quando a URL HTTPS está disponível. Ela não movimenta dados pessoais no banco central da Gestão Software nem compartilha a conta do sócio com o administrador da empresa.

Para cada cliente, o vínculo precisa apontar para o backend da **Base Universal NH v120** com `GS_TENANT_MODE=true`, banco isolado e chave exclusiva de importação. O próprio NH faz upload, validação, confirmação e auditoria.

Sem URL cadastrada ou HTTPS, o botão permanece bloqueado com explicação. Não realizamos migração automática de plataformas externas sem arquivo/exportação autorizada.

Esta versão não altera migrações Flyway do Control Plane nem apaga empresas existentes.
