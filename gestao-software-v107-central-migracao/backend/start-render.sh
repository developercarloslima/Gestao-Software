#!/bin/sh
set -eu
if [ -z "${DATABASE_URL:-}" ]; then
  : "${DATABASE_HOST:?DATABASE_HOST is required}"
  : "${DATABASE_PORT:=5432}"
  : "${DATABASE_NAME:?DATABASE_NAME is required}"
  export DATABASE_URL="jdbc:postgresql://${DATABASE_HOST}:${DATABASE_PORT}/${DATABASE_NAME}"
fi
exec java -jar /app/app.jar
