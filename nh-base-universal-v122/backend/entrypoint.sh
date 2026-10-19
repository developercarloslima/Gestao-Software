#!/bin/sh
set -eu

# Render Blueprint: monta a URL JDBC usando propriedades separadas do PostgreSQL.
if [ -z "${DATABASE_URL:-}" ] && [ -n "${DATABASE_HOST:-}" ]; then
  export DATABASE_URL="jdbc:postgresql://${DATABASE_HOST}:${DATABASE_PORT:-5432}/${DATABASE_NAME:-nh_plataforma}"
fi

# Compatibilidade com a Internal Database URL/connectionString do Render.
# Converte postgresql://usuario:senha@host:porta/banco para as propriedades JDBC.
case "${DATABASE_URL:-}" in
  postgres://*|postgresql://*)
    RAW_DATABASE_URL="${DATABASE_URL#*://}"
    DATABASE_AUTH="${RAW_DATABASE_URL%%@*}"
    DATABASE_HOST_PATH="${RAW_DATABASE_URL#*@}"

    if [ "$DATABASE_AUTH" != "$RAW_DATABASE_URL" ]; then
      DATABASE_USER_FROM_URL="${DATABASE_AUTH%%:*}"
      DATABASE_PASSWORD_FROM_URL="${DATABASE_AUTH#*:}"
      export DATABASE_USERNAME="${DATABASE_USERNAME:-$DATABASE_USER_FROM_URL}"
      export DATABASE_PASSWORD="${DATABASE_PASSWORD:-$DATABASE_PASSWORD_FROM_URL}"
      export DATABASE_URL="jdbc:postgresql://${DATABASE_HOST_PATH}"
    else
      export DATABASE_URL="jdbc:postgresql://${RAW_DATABASE_URL}"
    fi
    ;;
esac

exec java -jar /app/app.jar
