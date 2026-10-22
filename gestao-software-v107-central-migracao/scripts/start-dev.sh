#!/usr/bin/env bash
# Banco exclusivo e portas loopback; não carrega .env nem utiliza DATABASE_URL herdada.
set -euo pipefail
cd "$(dirname "$0")/.."
for key in GESTAO_BOOTSTRAP_CARLOS_PASSWORD GESTAO_BOOTSTRAP_PEDRO_PASSWORD; do
  value=${!key:-}
  [[ ${#value} -ge 8 ]] || { echo "$key precisa ter pelo menos 8 caracteres." >&2; exit 1; }
done
for tool in java python3 initdb pg_ctl psql; do command -v "$tool" >/dev/null || { echo "Requisito ausente: $tool" >&2; exit 1; }; done
[[ -f backend/target/control-plane-1.0.0.jar && -f web/site-src/dist/index.html ]] || { echo 'Execute scripts/setup-dev.sh primeiro.' >&2; exit 1; }
state="$PWD/.dev"
mkdir -p "$state/socket"
chmod 700 "$state" "$state/socket"
if [[ ! -f "$state/postgres/PG_VERSION" ]]; then
  initdb -D "$state/postgres" -U gestao --auth-local=trust --auth-host=trust --encoding=UTF8 --locale=C > "$state/initdb.log"
fi
python3 - <<'CHECK_PORTS'
import socket
for port in (55432, 8080, 3100):
    with socket.socket() as sock:
        try:
            sock.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'Porta local {port} já ocupada; nenhum serviço existente será encerrado.')
CHECK_PORTS
backend_pid=''; web_pid=''; started_db=false
cleanup() {
  trap - EXIT INT TERM
  [[ -z "$web_pid" ]] || kill "$web_pid" 2>/dev/null || true
  [[ -z "$backend_pid" ]] || { kill "$backend_pid" 2>/dev/null || true; wait "$backend_pid" 2>/dev/null || true; }
  if $started_db; then pg_ctl -D "$state/postgres" -m fast -w stop >/dev/null; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Sem reutilizar silenciosamente um servidor que ocupe a porta.
pg_ctl -D "$state/postgres" -l "$state/postgres.log" -o "-h 127.0.0.1 -p 55432 -k $state/socket" -w start
started_db=true
if ! psql -h "$state/socket" -p 55432 -U gestao -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='gestao_dev'" | grep -q 1; then
  psql -h "$state/socket" -p 55432 -U gestao -d postgres -c 'CREATE DATABASE gestao_dev' >/dev/null
fi
if [[ ! -f "$state/token-secret" ]]; then
  (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(48))' > "$state/token-secret")
fi
DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/gestao_dev \
DATABASE_USERNAME=gestao DATABASE_PASSWORD='' \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55432/gestao_dev \
SPRING_DATASOURCE_USERNAME=gestao SPRING_DATASOURCE_PASSWORD='' \
SPRING_PROFILES_ACTIVE=dev SPRING_APPLICATION_JSON='{}' \
SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080 PORT=8080 \
AUTH_TOKEN_SECRET="$(cat "$state/token-secret")" \
AUTO_PROVISION_ENABLED=false CLOUD_ORCHESTRATOR_ENABLED=false CLOUD_ROUTING_ENABLED=false \
CLOUD_DNS_PROVIDER=MANUAL GESTAO_ASAAS_API_KEY='' CLOUDFLARE_DNS_TOKEN='' \
java -jar backend/target/control-plane-1.0.0.jar \
  --spring.config.location=classpath:/application.yml \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/gestao_dev \
  --spring.datasource.username=gestao --spring.datasource.password= \
  --spring.flyway.url=jdbc:postgresql://127.0.0.1:55432/gestao_dev \
  --spring.flyway.user=gestao --spring.flyway.password= \
  --app.provisioning.auto-enabled=false --app.cloud.orchestrator-enabled=false \
  --app.provisioning.cloud-routing-enabled=false --app.cloud.dns-provider=MANUAL > "$state/backend.log" 2>&1 &
backend_pid=$!
python3 scripts/serve-dev.py > "$state/web.log" 2>&1 &
web_pid=$!
for attempt in {1..60}; do
  kill -0 "$backend_pid" 2>/dev/null && kill -0 "$web_pid" 2>/dev/null || { echo 'Falha na inicialização; consulte .dev/backend.log e .dev/web.log.' >&2; exit 1; }
  if python3 - <<'HEALTH'
from urllib.request import build_opener, ProxyHandler
try:
    with build_opener(ProxyHandler({})).open('http://127.0.0.1:3100/api/health', timeout=1) as response:
        assert response.status == 200
except Exception:
    raise SystemExit(1)
HEALTH
  then break; fi
  [[ "$attempt" -lt 60 ]] || { echo 'Timeout de inicialização; consulte .dev/.' >&2; exit 1; }
  sleep 1
done
echo 'Site/admin: http://127.0.0.1:3100 | API: http://127.0.0.1:8080 | PostgreSQL: 127.0.0.1:55432/gestao_dev'
echo 'Logs em .dev/. Ctrl+C encerra os serviços iniciados por este script.'
wait -n "$backend_pid" "$web_pid"
