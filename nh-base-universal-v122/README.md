# NH — Base Universal v122 (somente novas empresas)

**Nunca use este projeto para atualizar a instalação operacional da Novo Horizonte.**
Ele possui migrações próprias `db/tenant` e catálogos vazios por cliente.

Ponto de entrada: `README_ATUALIZACAO_BASE_V122.md`.
O único Compose local homologado pelo roteiro é `docker-compose.tenant-homologacao.yml`
e ele utiliza PostgreSQL em memória, sem volumes de clientes existentes.

O `backend/Dockerfile` também usa agora o perfil Maven `tenant` por padrão.
Migrações legadas continuam arquivadas apenas como referência histórica no
código e são excluídas do artefato compilado da base.

**Produção continua bloqueada** até Maven/Flyway/Prisma/WebAuthn/backup/Render testados.
