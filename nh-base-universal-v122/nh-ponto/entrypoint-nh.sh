#!/bin/sh
set -eu

if [ "${GS_TENANT_MODE:-false}" != "true" ]; then
  echo >&2 "[BLOQUEADO] Base universal exclusiva de novos tenants. Não executa seed legado."
  exit 65
fi

if ! find prisma/migrations -name migration.sql -type f -print -quit 2>/dev/null | grep -q .; then
  echo >&2 "[BLOQUEADO] Nenhuma migration Prisma versionada foi encontrada. Gere e revise o baseline antes do deploy."
  exit 64
fi

echo "[Apontamento Digital] Aplicando migrations Prisma versionadas..."
npx prisma migrate deploy

echo "[Apontamento Digital] Garantindo usuários padrão..."
if [ "${GS_TENANT_MODE:-false}" != "true" ]; then
  echo >&2 "[BLOQUEADO] Esta imagem é exclusiva para tenants isolados. Use o pacote legado separado para NH."
  exit 65
fi
echo "[Apontamento Digital] Inicializando somente a empresa autorizada..."
npx tsx prisma/seed-tenant.ts

if [ -z "${FACE_API_URL:-}" ] && [ -n "${FACE_API_HOST:-}" ]; then
  export FACE_API_URL="http://${FACE_API_HOST}:${FACE_API_PORT:-10000}"
fi

echo "[Apontamento Digital] Iniciando API..."
exec node apps/api/dist/server.js
