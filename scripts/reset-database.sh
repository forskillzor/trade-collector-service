#!/bin/bash
set -e

APP_DIR="/opt/trade-collector"
CONFIG_FILE="/etc/default/trade-collector"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ $CONFIG_FILE не найден"
    exit 1
fi

set -a
source "$CONFIG_FILE"
set +a

if [ -z "$DB_PASSWORD" ]; then
    echo "❌ DB_PASSWORD не установлен"
    exit 1
fi

PG_HOST="${DB_HOST:-localhost}"
PG_PORT="${DB_PORT:-5432}"
PG_USER="${DB_USER:-trade_user}"
PG_NAME="${DB_NAME:-trade_collector}"

DB_TABLES="raw_trades, filtered_trades, aggregates, volume_windows"

cat << WARN
╔══════════════════════════════════════════════════════════╗
║ ⚠️  ВНИМАНИЕ: Полный сброс БД                           ║
║                                                        ║
║ Будут удалены ВСЕ данные из таблиц:                     ║
║   $DB_TABLES
║                                                        ║
║ Бэкап будет создан автоматически перед сбросом.         ║
╚══════════════════════════════════════════════════════════╝
WARN

read -p "Введите 'YES' для подтверждения: " CONFIRM
if [ "$CONFIRM" != "YES" ]; then
    echo "❌ Сброс отменён"
    exit 0
fi

echo ""
echo "💾 Шаг 1/3: Создаю бэкап..."
if [ -f "$APP_DIR/backup-db.sh" ]; then
    bash "$APP_DIR/backup-db.sh" || {
        echo "❌ Бэкап не удался, сброс отменён"
        exit 1
    }
else
    echo "⚠️ backup-db.sh не найден, пропускаю бэкап"
fi

echo ""
echo "🗑️  Шаг 2/3: Удаляю таблицы..."
PGPASSWORD="$DB_PASSWORD" psql \
    -h "$PG_HOST" \
    -p "$PG_PORT" \
    -U "$PG_USER" \
    -d "$PG_NAME" \
    -c "DROP TABLE IF EXISTS $DB_TABLES CASCADE;"

echo "✅ Таблицы удалены"

echo ""
echo "📄 Шаг 3/3: Применяю схему заново..."
if [ -f "$APP_DIR/init-database.sh" ]; then
    bash "$APP_DIR/init-database.sh"
elif [ -f "$APP_DIR/current/init-database.sh" ]; then
    bash "$APP_DIR/current/init-database.sh"
else
    echo "❌ init-database.sh не найден"
    exit 1
fi

echo ""
echo "✅ БД успешно сброшена и пересоздана"
