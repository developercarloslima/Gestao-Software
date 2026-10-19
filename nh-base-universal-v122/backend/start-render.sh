#!/bin/sh
set -eu
if [ -z "${DATABASE_URL:-}" ]; then
  : "${DATABASE_HOST:?DATABASE_HOST is required}"
  : "${DATABASE_PORT:=5432}"
  : "${DATABASE_NAME:?DATABASE_NAME is required}"
  export DATABASE_URL="jdbc:postgresql://${DATABASE_HOST}:${DATABASE_PORT}/${DATABASE_NAME}"
fi
if [ -z "${NH_PONTO_INTERNAL_URL:-}" ] && [ -n "${NH_PONTO_INTERNAL_HOST:-}" ]; then
  export NH_PONTO_INTERNAL_URL="http://${NH_PONTO_INTERNAL_HOST}:${NH_PONTO_INTERNAL_PORT:-10000}"
fi
if [ -z "${FACE_API_INTERNAL_URL:-}" ] && [ -n "${FACE_API_HOST:-}" ]; then
  export FACE_API_INTERNAL_URL="http://${FACE_API_HOST}:${FACE_API_PORT:-10000}"
fi
exec java -jar /app/app.jar
