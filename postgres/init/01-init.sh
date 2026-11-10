#!/bin/bash
# Runs once, on first start of an empty data volume.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS vector;
    CREATE ROLE ${TODO_DB_USER} WITH LOGIN PASSWORD '${TODO_DB_PASSWORD}';
    CREATE DATABASE ${TODO_DB_NAME} OWNER ${TODO_DB_USER};
    CREATE ROLE ${AUTH_DB_USER} WITH LOGIN PASSWORD '${AUTH_DB_PASSWORD}';
    CREATE DATABASE ${AUTH_DB_NAME} OWNER ${AUTH_DB_USER};
EOSQL

# pgvector must be created by a superuser; make it available in the app DB.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$TODO_DB_NAME" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
