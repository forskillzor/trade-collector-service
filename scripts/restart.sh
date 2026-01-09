#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_NAME="trade-collector"

echo "🔄 Перезапуск $APP_NAME..."

# 1. Остановка
if [ -f "$SCRIPT_DIR/stop.sh" ]; then
    bash "$SCRIPT_DIR/stop.sh"
else
    echo "⚠️ stop.sh не найден, останавливаем через systemctl..."
    sudo systemctl stop $APP_NAME || true
fi

# 2. Небольшая пауза
sleep 3

# 3. Запуск
if [ -f "$SCRIPT_DIR/start.sh" ]; then
    bash "$SCRIPT_DIR/start.sh"
else
    echo "⚠️ start.sh не найден, запускаем через systemctl..."
    sudo systemctl start $APP_NAME
    sudo systemctl enable $APP_NAME
fi

# 4. Проверка здоровья
if [ -f "$SCRIPT_DIR/health-check.sh" ]; then
    echo "⏳ Ожидание запуска..."
    sleep 10
    bash "$SCRIPT_DIR/health-check.sh"
else
    echo "⚠️ health-check.sh не найден, проверяем статус..."
    sleep 5
    sudo systemctl status $APP_NAME --no-pager
fi

echo "✅ $APP_NAME перезапущен"