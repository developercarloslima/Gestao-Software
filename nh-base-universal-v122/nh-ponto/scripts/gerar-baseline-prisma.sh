#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Only for new, isolated tenants; do not execute against existing NH databases.
VERSION=20261010_tenant_initial
DEST="prisma/migrations/$VERSION"
mkdir -p "$DEST"
if [[ -s "$DEST/migration.sql" ]]; then
  echo "Baseline já existe e não deve ser sobrescrito. Crie uma nova migration incremental." >&2
  exit 1
fi
npx prisma validate
npx prisma migrate diff --from-empty --to-schema-datamodel prisma/schema.prisma --script > "$DEST/migration.sql"
test -s "$DEST/migration.sql"
cat > prisma/migrations/migration_lock.toml <<EOF
provider = "postgresql"
EOF
echo "Baseline gerado em $DEST/migration.sql. Revise e execute prisma migrate deploy somente em banco descartável."
