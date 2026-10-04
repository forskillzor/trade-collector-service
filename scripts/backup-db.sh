#!/bin/bash
set -e

BACKUP_DIR="/opt/trade-collector/backups"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_FILE="$BACKUP_DIR/backup-$TIMESTAMP.sql.gz"

mkdir -p "$BACKUP_DIR"

if [ -f /etc/default/trade-collector ]; then
    set -a
    source /etc/default/trade-collector
    set +a
fi

if [ -z "$DB_PASSWORD" ]; then
    echo "❌ DB_PASSWORD не установлен"
    exit 1
fi

echo "💾 Создаю бэкап: $BACKUP_FILE..."

PGPASSWORD="$DB_PASSWORD" pg_dump \
    -h "${DB_HOST:-localhost}" \
    -p "${DB_PORT:-5432}" \
    -U "${DB_USER:-trade_user}" \
    -d "${DB_NAME:-trade_collector}" \
    --no-owner --no-acl | gzip > "$BACKUP_FILE"

echo "✅ Бэкап сохранён: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"

KEEP=5
ls -t "$BACKUP_DIR"/backup-*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read old; do
    echo "   Удаляю старый: $(basename "$old")"
    rm -f "$old"
done
