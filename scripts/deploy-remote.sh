#!/bin/bash
set -e

echo "=== REMOTE DEPLOYMENT STARTED ==="

# Проверяем обязательные переменные
if [ -z "$DB_PASSWORD" ]; then
    echo "❌ ERROR: DB_PASSWORD is not set"
    exit 1
fi

# Параметры
APP_NAME='trade-collector'
APP_USER='deploy'
APP_DIR="/opt/$APP_NAME"

echo "📦 Deploying from: $(pwd)"
echo "📁 App dir: $APP_DIR"
echo "🏠 Database: ${DB_HOST:-localhost}:${DB_PORT:-6432}/${DB_NAME:-trade_collector}"

# 1. Проверяем, что файлы уже загружены в текущей директории
echo "🔍 Checking required files in current directory..."

REQUIRED_FILES=("trade-collector.jar" "config.json" "trade-collector.service" "run.sh" "init-database.sh")

for file in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$file" ]; then
        echo "❌ ERROR: Required file $file not found in $(pwd)"
        echo "📁 Files available:"
        ls -la
        exit 1
    fi
done

echo "✅ All required files found"

# Проверяем размер JAR
JAR_SIZE=$(stat -c%s trade-collector.jar 2>/dev/null || stat -f%z trade-collector.jar 2>/dev/null || echo 0)
if [ $JAR_SIZE -lt 1000000 ]; then
    echo "❌ ERROR: JAR file is too small! ($JAR_SIZE bytes)"
    echo "Expected ~35MB, got $((JAR_SIZE/1024/1024))MB"
    echo "File might be corrupted"
    exit 1
fi

echo "✅ JAR file size: $((JAR_SIZE/1024/1024))MB"

# 2. Устанавливаем Java если нужно
echo "📦 Checking Java..."
if ! command -v java &> /dev/null; then
    echo "Installing Java 21..."
    sudo apt-get update
    sudo apt-get install -y openjdk-21-jre-headless
elif ! java -version 2>&1 | grep -q '"21'; then
    echo "⚠️ Java 21 not found, installing..."
    sudo apt-get update
    sudo apt-get install -y openjdk-21-jre-headless
fi

# 3. Создаем пользователя если нужно
if ! id "$APP_USER" &>/dev/null; then
    echo "👤 Creating user $APP_USER..."
    sudo useradd -m -s /bin/bash "$APP_USER" || true
fi

# 4. Создаем директории
echo "📁 Creating directories..."
sudo mkdir -p "$APP_DIR" "/var/log/$APP_NAME"
sudo mkdir -p "$APP_DIR/backups"

# 6. Останавливаем сервис
echo "🛑 Stopping service..."
sudo systemctl stop "$APP_NAME.service" 2>/dev/null || true

# 7. Копируем файлы
echo "📄 Copying files..."
sudo cp -v trade-collector.jar config.json "$APP_DIR/"
sudo cp -v trade-collector.service run.sh init-database.sh "$APP_DIR/"
sudo cp -v 001_init_schema.sql "$APP_DIR/001_init_schema.sql"

# 8. Создаем environment файл
echo "🔒 Creating environment file..."
sudo tee /etc/default/trade-collector > /dev/null << EOF
# Database configuration
DB_PASSWORD='$DB_PASSWORD'
DB_HOST='${DB_HOST:-localhost}'
DB_PORT='${DB_PORT:-6432}'
DB_USER='${DB_USER:-trade_user}'
DB_NAME='${DB_NAME:-trade_collector}'

# Application
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
EOF

sudo chown root:deploy /etc/default/trade-collector
sudo chmod 640 /etc/default/trade-collector

# 9. Устанавливаем права
echo "🔐 Setting permissions..."
sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR/run.sh" "$APP_DIR/init-database.sh"
sudo chmod 644 "$APP_DIR/trade-collector.jar" "$APP_DIR/config.json" 2>/dev/null || true

# 10. Настраиваем systemd сервис
echo "⚙️ Configuring systemd service..."
sudo cp "$APP_DIR/trade-collector.service" /etc/systemd/system/
echo "✅ Service file copied to /etc/systemd/system/"

# 11. Инициализируем базу данных (если нужно)
if [ -n "$DB_PASSWORD" ] && [ -f "$APP_DIR/init-database.sh" ]; then
    echo "🗄️ Initializing database..."
    sudo chmod +x "$APP_DIR/init-database.sh"
    cd "$APP_DIR"
    export DB_PASSWORD DB_HOST DB_PORT DB_USER DB_NAME
    sudo -u deploy ./init-database.sh
fi

# 12. Перезагружаем и запускаем сервис
echo "🔄 Reloading systemd..."
sudo systemctl daemon-reload
sudo systemctl enable "$APP_NAME.service"

echo "🚀 Starting service..."
sudo systemctl start "$APP_NAME.service"

# 13. Проверяем статус
echo "📊 Checking service status..."
sleep 3
sudo systemctl status "$APP_NAME.service" --no-pager -l

echo "=== DEPLOYMENT COMPLETED SUCCESSFULLY ==="