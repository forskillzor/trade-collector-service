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

cat << WARN
╔══════════════════════════════════════════════════════════╗
║ ⚠️  ВНИМАНИЕ: Полный сброс БД                           ║
║                                                        ║
║ Будут удалены ВСЕ per-symbol таблицы:                  ║
║   raw_trades_*, aggregates_*,                          ║
║   filtered_trades_*, volume_windows_*                  ║
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
echo "🗑️  Шаг 2/3: Удаляю per-symbol таблицы..."
PGPASSWORD="$DB_PASSWORD" psql \
    -h "$PG_HOST" \
    -p "$PG_PORT" \
    -U "$PG_USER" \
    -d "$PG_NAME" \
    -c "
DO \$\$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT tablename FROM pg_catalog.pg_tables
        WHERE schemaname = 'public'
          AND (tablename LIKE 'raw_trades_%'
            OR tablename LIKE 'aggregates_%'
            OR tablename LIKE 'filtered_trades_%'
            OR tablename LIKE 'volume_windows_%')
    LOOP
        EXECUTE 'DROP TABLE IF EXISTS ' || quote_ident(r.tablename) || ' CASCADE';
        RAISE NOTICE 'Dropped %', r.tablename;
    END LOOP;
END
\$\$;
"

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
