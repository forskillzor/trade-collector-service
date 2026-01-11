#!/bin/bash
set -e

APP_NAME='trade-collector'
APP_USER='deploy'
APP_DIR="/opt/$APP_NAME"

echo "=== DEPLOYMENT STARTED ==="
echo "📊 Deployment variables:"
echo "  DB_HOST: $DB_HOST"
echo "  DB_PORT: $DB_PORT"
echo "  DB_USER: $DB_USER"
echo "  DB_NAME: $DB_NAME"
echo "  DB_PASSWORD: ******"
echo ""

echo "=== DEPLOYMENT STARTED ==="
echo "📋 Deployment environment variables:"
echo "  DB_HOST: $DB_HOST"
echo "  DB_PORT: $DB_PORT"
echo "  DB_USER: $DB_USER"
echo "  DB_NAME: $DB_NAME"
echo "  DB_PASSWORD: ******"
echo ""

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

# 4. Создаем environment файл для systemd
echo "🔒 Creating systemd environment file..."
sudo tee /etc/default/trade-collector > /dev/null << EOF
# Database configuration
DB_PASSWORD='$DB_PASSWORD'
DB_HOST='$DB_HOST'
DB_PORT='$DB_PORT'
DB_USER='$DB_USER'
DB_NAME='$DB_NAME'

# Application
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
EOF

sudo chmod 600 /etc/default/trade-collector
echo "✅ Environment file created: /etc/default/trade-collector"

# 5. Устанавливаем права
echo "🔐 Setting permissions..."
sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR/run.sh"
sudo chmod 644 "$APP_DIR/trade-collector.jar" "$APP_DIR/config.json"

# 6. Настраиваем systemd сервис (копируем готовый файл)
echo "⚙️ Configuring systemd service..."
if [ -f "$APP_DIR/trade-collector.service" ]; then
    sudo cp "$APP_DIR/trade-collector.service" /etc/systemd/system/
    echo "✅ Service file copied to /etc/systemd/system/"
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
sleep 3
sudo systemctl status $APP_NAME.service --no-pager -l

# 10. Проверяем что переменные передаются
echo "🔍 Checking environment variables in service..."
sudo systemctl show trade-collector.service | grep -i environment

echo "=== DEPLOYMENT COMPLETED ==="