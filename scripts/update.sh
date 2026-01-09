#!/bin/bash
set -e

APP_NAME="trade-collector"
APP_DIR="/opt/$APP_NAME"
BACKUP_DIR="/var/backups/$APP_NAME"
DATE=$(date +%Y%m%d_%H%M%S)

echo "🔄 Обновление $APP_NAME..."

# 1. Бэкап текущей версии
echo "💾 Создание бэкапа..."
sudo mkdir -p $BACKUP_DIR
sudo cp $APP_DIR/trade-collector $BACKUP_DIR/trade-collector.backup.$DATE
sudo cp $APP_DIR/config.json $BACKUP_DIR/config.json.backup.$DATE

# 2. Остановка сервиса
bash $(dirname "$0")/stop.sh

# 3. Копирование новой версии (предполагаем, что файл уже в /tmp)
if [ -f "/tmp/trade-collector" ]; then
    echo "📥 Копирование новой версии..."
    sudo cp /tmp/trade-collector $APP_DIR/
    sudo chmod +x $APP_DIR/trade-collector
    sudo chown trader:trader $APP_DIR/trade-collector
fi

if [ -f "/tmp/config.json" ]; then
    echo "📁 Обновление конфига..."
    sudo cp /tmp/config.json $APP_DIR/config.json
    sudo chown trader:trader $APP_DIR/config.json
fi

# 4. Запуск сервиса
bash $(dirname "$0")/start.sh

# 5. Валидация
echo "🔍 Проверка обновления..."
VERSION=$($APP_DIR/trade-collector --version 2>/dev/null || curl -s http://localhost:8080/ | grep "Version" | head -1)
echo "✅ Новая версия: $VERSION"

echo "🎉 Обновление завершено!"