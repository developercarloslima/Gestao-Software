#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in java javac mvn node pnpm python3 initdb pg_ctl psql; do
  command -v "$tool" >/dev/null || { echo "Instale o requisito ausente: $tool" >&2; exit 1; }
done
javac -version 2>&1 | grep -Eq '^javac 21([. ]|$)' || { echo 'Use um JDK 21 completo (JAVA_HOME e PATH).' >&2; exit 1; }
mvn -B -f backend/pom.xml verify
pnpm --dir web/site-src install --frozen-lockfile
pnpm --dir web/site-src build
python3 tests/cloud/release_guard_test.py
node tests/cloud/cloud_ui_test.cjs
mkdir -p .dev/java-tests
javac -cp backend/target/classes -d .dev/java-tests tests/cloud/CloudDnsValidationTest.java tests/cloud/PlacementPolicyTest.java
java -cp backend/target/classes:.dev/java-tests CloudDnsValidationTest
java -cp backend/target/classes:.dev/java-tests PlacementPolicyTest
echo 'Builds e testes concluídos. Inicie com scripts/start-dev.sh.'
