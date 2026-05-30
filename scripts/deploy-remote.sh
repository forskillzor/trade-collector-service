#!/bin/bash
set -e
VERSION="${1:?Usage: $0 v1.0.0}"
APP_DIR="/opt/trade-collector"
RELEASE_DIR="$APP_DIR/releases/$VERSION"
HEALTH_URL="http://localhost:8080/health"
HEALTH_RETRIES=10
HEALTH_DELAY=3

echo "═"$(printf '═%.0s' {1..50})
echo "📦 Deploying $VERSION"
echo "═"$(printf '═%.0s' {1..50})

# --- 1. Проверить архив -------------------------------------------------------
ARCHIVE="/tmp/trade-collector-$VERSION.tar.gz"
if [ ! -f "$ARCHIVE" ]; then
    echo "❌ Архив не найден: $ARCHIVE"
    exit 1
fi

if ! tar -tzf "$ARCHIVE" >/dev/null 2>&1; then
    echo "❌ Архив повреждён: $ARCHIVE"
    exit 1
fi
echo "✅ Архив проверен: $(du -h "$ARCHIVE" | cut -f1)"

# --- 2. Загрузить переменные БД -----------------------------------------------
if [ -f /etc/default/trade-collector ]; then
    set -a
    source /etc/default/trade-collector
    set +a
fi

if [ -z "$DB_PASSWORD" ]; then
    echo "❌ DB_PASSWORD не найден в /etc/default/trade-collector"
    exit 1
fi
echo "✅ Конфигурация БД загружена"

# --- 3. Бэкап БД --------------------------------------------------------------
if [ "${SKIP_BACKUP:-false}" != "true" ] && [ -f "$APP_DIR/backup-db.sh" ]; then
    echo "💾 Создаю бэкап БД..."
    bash "$APP_DIR/backup-db.sh" || echo "⚠️ Бэкап не удался, продолжаю..."
else
    echo "⏭️ Бэкап пропущен (SKIP_BACKUP=$SKIP_BACKUP)"
fi

# --- 4. Запомнить предыдущую версию -------------------------------------------
PREV_VERSION=""
if [ -L "$APP_DIR/current" ]; then
    PREV_VERSION=$(readlink "$APP_DIR/current" | xargs basename 2>/dev/null || echo "")
    echo "📋 Предыдущая версия: $PREV_VERSION"
else
    echo "📋 Первый деплой (нет предыдущей версии)"
fi

# --- 5. Распаковать в releases/$VERSION ---------------------------------------
echo "📂 Распаковка в $RELEASE_DIR..."
mkdir -p "$RELEASE_DIR"
tar -xzf "$ARCHIVE" -C "$RELEASE_DIR"
chmod +x "$RELEASE_DIR"/*.sh 2>/dev/null || true
echo "✅ Файлы распакованы"

# --- 6. Остановить сервис -----------------------------------------------------
echo "🛑 Останавливаю сервис..."
systemctl stop trade-collector.service 2>/dev/null || true
sleep 2

# --- 7. Создать структуру если первый деплой ----------------------------------
mkdir -p "$APP_DIR/releases" "$APP_DIR/backups" "$APP_DIR/logs"
cp "$APP_DIR/backup-db.sh" "$APP_DIR/backup-db.sh" 2>/dev/null || true

# --- 8. Атомарно переключить symlink ------------------------------------------
echo "🔗 Переключаю symlink: current → releases/$VERSION"
ln -sfn "releases/$VERSION" "$APP_DIR/current"

# --- 9. Применить миграции БД -------------------------------------------------
if [ -f "$RELEASE_DIR/init-database.sh" ]; then
    echo "🗄️ Применяю миграции БД..."
    cd "$RELEASE_DIR"
    bash init-database.sh || echo "⚠️ Миграции не применились, продолжаю..."
fi

# --- 10. Запустить сервис -----------------------------------------------------
echo "🚀 Запускаю сервис..."
systemctl start trade-collector.service

# --- 11. Health check с повторными попытками ----------------------------------
echo "🏥 Проверяю здоровье сервиса..."
for i in $(seq 1 $HEALTH_RETRIES); do
    if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
        echo "✅ Health check OK (попытка $i)"
        break
    fi

    if [ "$i" -eq "$HEALTH_RETRIES" ]; then
        echo "❌ Health check провален после $HEALTH_RETRIES попыток"

        if [ -n "$PREV_VERSION" ] && [ -d "$APP_DIR/releases/$PREV_VERSION" ]; then
            echo "🔙 Откатываю на $PREV_VERSION..."
            systemctl stop trade-collector.service 2>/dev/null || true
            ln -sfn "releases/$PREV_VERSION" "$APP_DIR/current"
            systemctl start trade-collector.service

            if curl -sf "$HEALTH_URL" > /dev/null 2>&1; then
                echo "✅ Откат на $PREV_VERSION успешен"
            else
                echo "❌ Откат тоже провалился! Требуется ручное вмешательство."
            fi
        fi
        exit 1
    fi

    sleep $HEALTH_DELAY
done

# --- 12. Очистка старых версий ------------------------------------------------
echo "🧹 Чищу старые релизы (оставляю 3 последних)..."
ls -dt "$APP_DIR"/releases/*/ 2>/dev/null | tail -n +4 | while read old; do
    echo "   Удаляю: $old"
    rm -rf "$old"
done

echo "═"$(printf '═%.0s' {1..50})
echo "✅ Деплой $VERSION завершён успешно"
echo "═"$(printf '═%.0s' {1..50})
