#!/bin/bash
set -e

echo "🗄️ Database initialization started..."

# Загружаем переменные окружения из файла если он существует
if [ -f /etc/default/trade-collector ]; then
    echo "📄 Loading environment from /etc/default/trade-collector"
    # Безопасно загружаем переменные
    while IFS='=' read -r key value || [ -n "$key" ]; do
        # Пропускаем комментарии и пустые строки
        if [[ "$key" =~ ^[[:space:]]*# ]] || [[ -z "$key" ]] || [[ "$key" =~ ^[[:space:]]*$ ]]; then
            continue
        fi
        # Убираем кавычки
        value=${value#\'}
        value=${value%\'}
        value=${value#\"}
        value=${value%\"}

        # Экспортируем переменную
        export "$key"="$value"
    done < /etc/default/trade-collector
fi

# Проверяем переменные
if [ -z "$DB_PASSWORD" ]; then
    echo "❌ ERROR: DB_PASSWORD is not set"
    echo "Available variables:"
    env | grep DB_ || echo "No DB_ variables found"
    exit 1
fi

# Используем переменные с дефолтными значениями
PG_HOST="${DB_HOST:-localhost}"
PG_PORT="${DB_PORT:-5432}"
PG_USER="${DB_USER:-trade_user}"
PG_NAME="${DB_NAME:-trade_collector}"

echo "🔧 Configuring PostgreSQL at $PG_HOST:$PG_PORT..."
echo "📊 Using database: $PG_NAME, user: $PG_USER"

# Устанавливаем пароль для psql
export PGPASSWORD="$DB_PASSWORD"

# 1. Проверяем подключение к базе
echo "Testing connection..."
if ! psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_NAME" -c "\q" 2>/dev/null; then
    echo "❌ ERROR: Cannot connect to $PG_NAME at $PG_HOST:$PG_PORT"
    echo "Make sure the database exists (create it manually if first deploy):"
    echo "  CREATE DATABASE $PG_NAME OWNER $PG_USER;"
    exit 1
fi
echo "✅ Connected to $PG_NAME"

# 2. Выполняем SQL схему если она есть
SCHEMA_FILES=(
    "/tmp/deploy/sql/001_init_schema.sql"
    "/opt/trade-collector/sql/001_init_schema.sql"
    "./sql/001_init_schema.sql"
    "./001_init_schema.sql"
    "/opt/trade-collector/001_init_schema.sql"
)

for schema_file in "${SCHEMA_FILES[@]}"; do
    if [ -f "$schema_file" ]; then
        echo "Executing SQL schema from $schema_file..."
        psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_NAME" -f "$schema_file" 2>&1 || echo "⚠️ Schema apply had errors (may need superuser for extensions)"
        echo "✅ SQL schema processed"
        break
    fi
done

echo "✅ Database initialization completed!"