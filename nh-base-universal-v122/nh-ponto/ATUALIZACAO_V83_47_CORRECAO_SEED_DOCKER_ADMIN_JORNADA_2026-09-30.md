# V83.47 — correção de inicialização do ponto-api no Docker

## Problema corrigido
Na V83.46 o `prisma/seed.ts` importava `../apps/api/src/services/admin-attendance.service.js`.
A imagem final do `ponto-api` copia apenas `apps/api/dist` e `prisma`, portanto `apps/api/src` não existe em runtime.
Isso fazia o seed encerrar com `MODULE_NOT_FOUND` e o container entrar em loop de reinício/unhealthy.

## Correção
- Criado `prisma/admin-attendance.ts`, copiado para a imagem junto com a pasta `prisma`.
- `prisma/seed.ts` e `prisma/bootstrap.ts` usam esse helper local e não dependem mais de `apps/api/src`.
- A regra funcional permanece: ADMIN comum possui vínculo de jornada; SUPER_ADMIN continua isento.
- Nenhuma limpeza ou reset de banco é necessária.
- O schema Prisma não foi alterado nesta correção.

## Resultado esperado no startup
1. Prisma sincroniza o schema.
2. Seed garante usuários/vínculos sem erro de módulo ausente.
3. API inicia em `apps/api/dist/server.js`.
4. Healthcheck do `ponto-api` passa para `healthy`.
