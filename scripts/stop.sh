#!/bin/bash
set -e

APP_NAME="trade-collector"
SERVICE_NAME="$APP_NAME"

echo "🛑 Остановка $APP_NAME..."

# Остановка systemd сервиса
if systemctl is-active --quiet $SERVICE_NAME; then
    echo "⏳ Останавливаем systemd сервис..."
    sudo systemctl stop $SERVICE_NAME
    sudo systemctl disable $SERVICE_NAME
fi

# Убиваем процессы если остались
PIDS=$(ps aux | grep "[t]rade-collector" | awk '{print $2}')
if [ ! -z "$PIDS" ]; then
    echo "🧹 Убиваем оставшиеся процессы: $PIDS"
    sudo kill -9 $PIDS 2>/dev/null || true
fi

# Удаляем PID файл
PID_FILE="/var/run/$APP_NAME.pid"
if [ -f "$PID_FILE" ]; then
    sudo rm -f $PID_FILE
fi

# Очистка systemd
sudo systemctl daemon-reload
sudo systemctl reset-failed $SERVICE_NAME 2>/dev/null || true

echo "✅ $APP_NAME остановлен"