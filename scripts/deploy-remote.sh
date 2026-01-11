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
sudo cp -rv /tmp/deploy/* "$APP_DIR/"

# 4. Создаем systemd environment файл (НЕ .env для приложения)
echo "🔒 Creating systemd environment file..."
cat > "/tmp/${APP_NAME}.env" << EOF
# Database environment variables (override config.json)
DB_PASSWORD=$DB_PASSWORD
DB_HOST=$DB_HOST
DB_PORT=$DB_PORT
DB_USER=$DB_USER
DB_NAME=$DB_NAME

# Application environment
APP_NAME=$APP_NAME
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
EOF

sudo mv "/tmp/${APP_NAME}.env" "/etc/default/${APP_NAME}"
sudo chmod 600 "/etc/default/${APP_NAME}"

# 5. Устанавливаем права
echo "🔐 Setting permissions..."
sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR/run.sh"
sudo chmod 644 "$APP_DIR/trade-collector.jar" "$APP_DIR/config.json"

# 6. Настраиваем systemd сервис
echo "⚙️ Configuring systemd service..."
if [ -f "$APP_DIR/trade-collector.service" ]; then
    # Обновляем EnvironmentFile в service файле
    sudo cp "$APP_DIR/trade-collector.service" /etc/systemd/system/

    # Добавляем загрузку переменных окружения
    if ! grep -q "EnvironmentFile" /etc/systemd/system/$APP_NAME.service; then
        sudo sed -i '/\[Service\]/a EnvironmentFile=/etc/default/trade-collector' /etc/systemd/system/$APP_NAME.service
    fi

    # Проверяем пользователя
    sudo sed -i 's/User=trader/User=deploy/g' /etc/systemd/system/$APP_NAME.service
    sudo sed -i 's/Group=trader/Group=deploy/g' /etc/systemd/system/$APP_NAME.service
else
    echo "❌ ERROR: trade-collector.service not found!"
    exit 1
fi

# 7. Инициализируем базу данных
if [ -n "$DB_PASSWORD" ]; then
    echo "🗄️ Initializing database..."
    if [ -f "$APP_DIR/init-database.sh" ]; then
        echo "🔧 Running database initialization..."
        sudo chmod +x "$APP_DIR/init-database.sh"
        # Передаем все переменные БД
        export DB_PASSWORD DB_HOST DB_PORT DB_USER DB_NAME
        sudo -E "$APP_DIR/init-database.sh"
    fi
fi

# 8. Перезагружаем и запускаем сервис
echo "🔄 Reloading systemd..."
sudo systemctl daemon-reload
sudo systemctl enable $APP_NAME.service

echo "🚀 Starting service..."
sudo systemctl stop $APP_NAME.service 2>/dev/null || true
sudo systemctl start $APP_NAME.service

# 9. Проверяем статус
echo "📊 Checking service status..."
sleep 5
sudo systemctl status $APP_NAME.service --no-pager -l

echo "=== DEPLOYMENT COMPLETED ==="