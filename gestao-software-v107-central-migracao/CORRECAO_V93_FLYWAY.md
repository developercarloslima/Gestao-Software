# Gestão Software v93 — correção Flyway

## Erro corrigido
O backend não iniciava quando a pasta local possuía duas migrations com a versão V3:
- V3__repair_initial_owner_access.sql
- V3__reset_owner_passwords_2027.sql

Flyway exige uma única migration por versão.

## Estado correto desta versão
A pasta `backend/src/main/resources/db/migration` contém:
- V1__control_plane_schema.sql
- V2__catalog_and_initial_clients.sql
- V3__repair_initial_owner_access.sql

## Instalação local recomendada
Extraia este ZIP em uma pasta NOVA e vazia. Não extraia por cima da pasta de versões antigas.

No PowerShell, dentro da nova pasta:

```powershell
.\RESET_LOCAL_WINDOWS.ps1
```

Ou manualmente:

```powershell
docker compose down -v --remove-orphans
docker compose build --no-cache backend
docker compose build web
docker compose up -d
docker compose ps
```

Acesse `http://localhost:3100/admin/login.html`.
