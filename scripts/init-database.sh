#!/bin/bash
set -e

echo "🗄️ Database initialization started..."

# Проверяем переменные
if [ -z "$DB_PASSWORD" ]; then
    echo "❌ ERROR: DB_PASSWORD is not set"
    exit 1
fi

echo "🔧 Configuring PostgreSQL..."

# 1. Создаем базу данных если её нет
if ! sudo -u postgres psql -p 6432 -lqt | cut -d \| -f 1 | grep -qw trade_collector; then
    echo "Creating database trade_collector..."
    sudo -u postgres psql -p 6432 -c "CREATE DATABASE trade_collector;"
    echo "✅ Database created"
else
    echo "✅ Database trade_collector already exists"
fi

# 2. Создаем пользователя если его нет
if ! sudo -u postgres psql -p 6432 -c "\du" | grep -qw trade_user; then
    echo "Creating user trade_user..."
    sudo -u postgres psql -p 6432 -c "CREATE USER trade_user WITH PASSWORD '$DB_PASSWORD';"

    echo "✅ User created"
else
    echo "✅ User trade_user already exists"

    # Обновляем пароль на всякий случай
    echo "Updating user password..."
    sudo -u postgres psql -p 6432 -c "ALTER USER trade_user WITH PASSWORD '$DB_PASSWORD';"
    echo "✅ Password updated"
fi

# 3. Даем права
echo "Granting privileges..."
sudo -u postgres psql -p 6432 -c "GRANT ALL PRIVILEGES ON DATABASE trade_collector TO trade_user;"

# 4. Выполняем SQL схему если она есть
if [ -f "/tmp/deploy/001_init_schema.sql" ]; then
    echo "Executing SQL schema..."
    sudo -u postgres psql -p 6432 -d trade_collector -f /tmp/deploy/001_init_schema.sql
    echo "✅ SQL schema applied"
elif [ -f "/opt/trade-collector/001_init_schema.sql" ]; then
    echo "Executing SQL schema from /opt/trade-collector..."
    sudo -u postgres psql -p 6432 -d trade_collector -f /opt/trade-collector/001_init_schema.sql
    echo "✅ SQL schema applied"
else
    echo "⚠️ SQL schema file not found"
fi

echo "✅ Database initialization completed!"