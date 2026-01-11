#!/bin/bash
set -e

APP_NAME='trade-collector'
APP_USER='deploy'
APP_DIR="/opt/$APP_NAME"

echo "=== DEPLOYMENT STARTED ==="

# 1. Устанавливаем Java 21
echo "📦 Installing Java 21..."
if ! java -version 2>&1 | grep -q '"21\.'; then
    sudo apt-get update
    sudo apt-get install -y openjdk-21-jre-headless
    echo "✅ Java 21 installed"
else
    echo "✅ Java 21 already installed"
fi

# 2. Создаем структуру директорий
echo "📁 Creating directory structure..."
sudo mkdir -p "$APP_DIR" "/var/log/$APP_NAME"

# 3. Копируем файлы
echo "📄 Copying files..."
sudo cp -v /tmp/deploy/* "$APP_DIR/"

# 4. Устанавливаем права
echo "🔐 Setting permissions..."
sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR/run.sh"

# 5. Конфигурируем базу данных (если нужно)
if [ -n "$DB_PASSWORD" ]; then
    echo "🔧 Configuring database..."
    # Можно добавить конфигурацию базы данных здесь
fi

# 6. Устанавливаем и настраиваем сервис
echo "⚙️ Configuring systemd service..."
sudo cp "$APP_DIR/trade-collector.service" /etc/systemd/system/

# Обновляем пользователя в service файле если нужно
if grep -q "User=trader" /etc/systemd/system/$APP_NAME.service; then
    sudo sed -i 's/User=trader/User=deploy/' /etc/systemd/system/$APP_NAME.service
fi
if grep -q "Group=trader" /etc/systemd/system/$APP_NAME.service; then
    sudo sed -i 's/Group=trader/Group=deploy/' /etc/systemd/system/$APP_NAME.service
fi

# 7. Перезагружаем и запускаем сервис
echo "🔄 Reloading systemd..."
sudo systemctl daemon-reload
sudo systemctl enable $APP_NAME.service

echo "🚀 Starting service..."
sudo systemctl restart $APP_NAME.service

# 8. Проверяем статус
echo "📊 Checking service status..."
sleep 3
sudo systemctl status $APP_NAME.service --no-pager | head -20

echo "=== DEPLOYMENT COMPLETED ==="