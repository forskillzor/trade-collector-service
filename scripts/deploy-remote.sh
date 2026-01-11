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

if ! command -v envsubst &> /dev/null; then
    echo "📦 Installing gettext for envsubst..."
    sudo apt-get install -y gettext
fi

# 2. Создаем структуру директорий
echo "📁 Creating directory structure..."
sudo mkdir -p "$APP_DIR" "/var/log/$APP_NAME"

# 3. Копируем файлы
echo "📄 Copying files..."
sudo cp -rv /tmp/deploy/* "$APP_DIR/"
sudo cp config.json deploy-package/

# 4. Создаем .env файл для хранения секретов (ДО установки прав)
echo "🔒 Creating environment file..."
cat > "/tmp/.env_$APP_NAME" << EOF
# Database
DB_PASSWORD=$DB_PASSWORD

# Application
APP_NAME=trade-collector
APP_PORT=8080
EOF

sudo mv "/tmp/.env_$APP_NAME" "$APP_DIR/.env"
sudo chmod 600 "$APP_DIR/.env"

# 5. Устанавливаем права (ИСПРАВЛЕНО - убрали дублирование)
echo "🔐 Setting permissions..."
sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR/run.sh"
sudo chmod 644 "$APP_DIR/trade-collector.jar"  # JAR не нужны execute права
sudo chmod 644 "$APP_DIR/config.json"  # Добавляем права на config.json

# 6. Инициализируем базу данных (если есть пароль)
if [ -n "$DB_PASSWORD" ]; then
    echo "🗄️ Initializing database..."
    # Здесь можно вызвать init-database.sh если он есть
    if [ -f "$APP_DIR/init-database.sh" ]; then
        echo "🔧 Running database initialization..."
        sudo chmod +x "$APP_DIR/init-database.sh"
        # Экспортируем пароль для дочернего процесса
        export DB_PASSWORD
        sudo -E "$APP_DIR/init-database.sh"
    else
        echo "⚠️ init-database.sh not found, skipping database setup"
    fi
fi

# 7. Устанавливаем и настраиваем сервис
echo "⚙️ Configuring systemd service..."
if [ -f "$APP_DIR/trade-collector.service" ]; then
    sudo cp "$APP_DIR/trade-collector.service" /etc/systemd/system/

    # Обновляем пользователя в service файле если нужно
    if grep -q "User=trader" /etc/systemd/system/$APP_NAME.service; then
        sudo sed -i 's/User=trader/User=deploy/' /etc/systemd/system/$APP_NAME.service
    fi
    if grep -q "Group=trader" /etc/systemd/system/$APP_NAME.service; then
        sudo sed -i 's/Group=trader/Group=deploy/' /etc/systemd/system/$APP_NAME.service
    fi
else
    echo "❌ ERROR: trade-collector.service not found!"
    exit 1
fi

# 8. Перезагружаем и запускаем сервис
echo "🔄 Reloading systemd..."
sudo systemctl daemon-reload
sudo systemctl enable $APP_NAME.service

echo "🚀 Starting service..."
# Останавливаем если уже запущен
sudo systemctl stop $APP_NAME.service 2>/dev/null || true
sudo systemctl start $APP_NAME.service

# 9. Проверяем статус
echo "📊 Checking service status..."
sleep 8  # Даем больше времени на старт
echo "=== Service Status ==="
sudo systemctl status $APP_NAME.service --no-pager -l

# 10. Проверяем логи
echo "📋 Checking recent logs..."
sudo journalctl -u $APP_NAME.service -n 20 --no-pager || true

echo "=== DEPLOYMENT COMPLETED ==="