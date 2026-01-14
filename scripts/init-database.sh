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
PG_PORT="${DB_PORT:-6432}"
PG_USER="${DB_USER:-trade_user}"
PG_NAME="${DB_NAME:-trade_collector}"

echo "🔧 Configuring PostgreSQL at $PG_HOST:$PG_PORT..."
echo "📊 Using database: $PG_NAME, user: $PG_USER"

# Устанавливаем пароль для psql
export PGPASSWORD="$DB_PASSWORD"

# 1. Проверяем подключение
echo "Testing connection..."
if ! psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d postgres -c "\q" 2>/dev/null; then
    echo "❌ ERROR: Cannot connect to PostgreSQL at $PG_HOST:$PG_PORT"
    echo "Make sure:"
    echo "1. PostgreSQL/PgBouncer is running"
    echo "2. User $PG_USER exists"
    echo "3. Host $PG_HOST is accessible"
    exit 1
fi

# 2. Создаем базу данных если её нет
if ! psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d postgres -lqt 2>/dev/null | cut -d \| -f 1 | grep -qw "$PG_NAME"; then
    echo "Creating database $PG_NAME..."
    psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d postgres -c "CREATE DATABASE $PG_NAME;"
    echo "✅ Database created"
else
    echo "✅ Database $PG_NAME already exists"
fi

# 3. Даем права (если нужно)
echo "Granting privileges..."
psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_NAME" -c "GRANT ALL PRIVILEGES ON DATABASE $PG_NAME TO $PG_USER;"

# 4. Выполняем SQL схему если она есть
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
        psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_NAME" -f "$schema_file"
        echo "✅ SQL schema applied"
        break
    fi
done

echo "✅ Database initialization completed!"