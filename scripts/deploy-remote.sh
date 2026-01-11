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

# Тестируем sudo
echo "🔍 Testing sudo access..."
if sudo -n true 2>/dev/null; then
    echo "✅ Sudo works without password"
    SUDO_CMD="sudo"
else
    echo "⚠️ Trying sudo with echo..."
    # Пробуем передать пустой пароль через echo
    SUDO_CMD="sudo -S"
fi

# 1. Устанавливаем Java 21
echo "📦 Installing Java 21..."
if ! java -version 2>&1 | grep -q '"21\.'; then
    echo "" | $SUDO_CMD apt-get update -qq 2>/dev/null
    echo "" | $SUDO_CMD apt-get install -y -qq openjdk-21-jre-headless 2>/dev/null
    echo "✅ Java 21 installed"
else
    echo "✅ Java 21 already installed"
fi

# 2. Создаем структуру директорий с явной передачей пустого пароля
echo "📁 Creating directory structure..."
echo "" | $SUDO_CMD mkdir -p "$APP_DIR" "/var/log/$APP_NAME" 2>/dev/null

# 3. Копируем файлы
echo "📄 Copying files..."
echo "" | $SUDO_CMD cp -rv /tmp/deploy/* "$APP_DIR/" 2>/dev/null

# 4. Создаем environment файл для systemd
echo "🔒 Creating systemd environment file..."
cat << EOF | $SUDO_CMD tee /etc/default/trade-collector > /dev/null
# Database configuration
DB_PASSWORD='$DB_PASSWORD'
DB_HOST='$DB_HOST'
DB_PORT='$DB_PORT'
DB_USER='$DB_USER'
DB_NAME='$DB_NAME'

# Application
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
EOF

echo "" | $SUDO_CMD chmod 600 /etc/default/trade-collector 2>/dev/null
echo "✅ Environment file created: /etc/default/trade-collector"

# 5. Устанавливаем права
echo "🔐 Setting permissions..."
echo "" | $SUDO_CMD chown -R "$APP_USER:$APP_USER" "$APP_DIR" "/var/log/$APP_NAME" 2>/dev/null
echo "" | $SUDO_CMD chmod 755 "$APP_DIR/run.sh" 2>/dev/null
echo "" | $SUDO_CMD chmod 644 "$APP_DIR/trade-collector.jar" "$APP_DIR/config.json" 2>/dev/null

# 6. Настраиваем systemd сервис
echo "⚙️ Configuring systemd service..."
if [ -f "$APP_DIR/trade-collector.service" ]; then
    echo "" | $SUDO_CMD cp "$APP_DIR/trade-collector.service" /etc/systemd/system/ 2>/dev/null
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
        echo "" | $SUDO_CMD chmod +x "$APP_DIR/init-database.sh" 2>/dev/null
        export DB_PASSWORD DB_HOST DB_PORT DB_USER DB_NAME
        # Запускаем без sudo, так как скрипт сам использует psql
        "$APP_DIR/init-database.sh"
    fi
fi

# 8. Перезагружаем и запускаем сервис
echo "🔄 Reloading systemd..."
echo "" | $SUDO_CMD systemctl daemon-reload 2>/dev/null
echo "" | $SUDO_CMD systemctl enable $APP_NAME.service 2>/dev/null

echo "🚀 Starting service..."
echo "" | $SUDO_CMD systemctl stop $APP_NAME.service 2>/dev/null || true
echo "" | $SUDO_CMD systemctl start $APP_NAME.service 2>/dev/null

# 9. Проверяем статус
echo "📊 Checking service status..."
sleep 5
echo "" | $SUDO_CMD systemctl status $APP_NAME.service --no-pager -l 2>/dev/null

echo "=== DEPLOYMENT COMPLETED ==="