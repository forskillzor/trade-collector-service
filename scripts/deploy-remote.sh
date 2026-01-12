#!/bin/bash
set -e

echo "=== REMOTE DEPLOYMENT STARTED ==="

# Проверяем обязательные переменные
if [ -z "$DB_PASSWORD" ]; then
    echo "❌ ERROR: DB_PASSWORD is not set"
    exit 1
fi

if [ -z "$RELEASE_TAG" ]; then
    echo "⚠️ WARNING: RELEASE_TAG not set, using default"
    export RELEASE_TAG="v0.0.1"
fi

# Параметры
APP_NAME='trade-collector'
APP_USER='deploy'
APP_DIR="/opt/$APP_NAME"
REPO="forskillzor/TradeCollectorService"

echo "📦 Release: $RELEASE_TAG"
echo "📁 App dir: $APP_DIR"
echo "🏠 Database: ${DB_HOST:-localhost}:${DB_PORT:-6432}/${DB_NAME:-trade_collector}"

# 1. Создаем временную директорию
TEMP_DIR="/tmp/deploy-$(date +%s)"
mkdir -p "$TEMP_DIR"
cd "$TEMP_DIR"

echo "🔗 Downloading release from GitHub..."

# 2. Скачиваем релиз с GitHub
RELEASE_URL="https://github.com/$REPO/releases/download/$RELEASE_TAG"

# Скачиваем JAR
if ! curl -L -f -o trade-collector.jar \
  "$RELEASE_URL/trade-collector.jar"; then
  echo "❌ Failed to download JAR from: $RELEASE_URL/trade-collector.jar"
  echo "💡 Check if release $RELEASE_TAG exists and has trade-collector.jar"
  exit 1
fi

# Скачиваем конфиг
if ! curl -L -f -o config.json \
  "$RELEASE_URL/config.json"; then
  echo "⚠️ Failed to download config.json, using default"
  # Можно создать default config здесь
fi

# Проверяем скачанные файлы
if [ ! -f "trade-collector.jar" ]; then
    echo "❌ ERROR: JAR file not found after download"
    exit 1
fi

JAR_SIZE=$(stat -c%s trade-collector.jar 2>/dev/null || stat -f%z trade-collector.jar 2>/dev/null || echo 0)
if [ $JAR_SIZE -lt 1000000 ]; then  # Минимум 1MB
    echo "❌ ERROR: Downloaded JAR is too small! ($JAR_SIZE bytes)"
    echo "Expected ~35MB, got $((JAR_SIZE/1024/1024))MB"
    echo "File might be corrupted or release doesn't exist"
    exit 1
fi

echo "✅ Downloaded: trade-collector.jar ($((JAR_SIZE/1024/1024))MB)"

# 3. Скачиваем скрипты из основной ветки репозитория
echo "📄 Downloading deployment scripts..."
SCRIPTS_URL="https://raw.githubusercontent.com/$REPO/master/scripts"

for script in "trade-collector.service" "run.sh" "init-database.sh"; do
    if curl -L -f -o "$script" "$SCRIPTS_URL/$script"; then
        echo "✅ Downloaded $script"
    else
        echo "⚠️ Failed to download $script"
    fi
done

# 4. Устанавливаем Java если нужно
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

# 5. Создаем пользователя если нужно
if ! id "$APP_USER" &>/dev/null; then
    echo "👤 Creating user $APP_USER..."
    sudo useradd -m -s /bin/bash "$APP_USER" || true
fi

# 6. Создаем директории
echo "📁 Creating directories..."
sudo mkdir -p "$APP_DIR" "/var/log/$APP_NAME"
sudo mkdir -p "$APP_DIR/backups"

# 7. Резервируем старую версию
if [ -f "$APP_DIR/trade-collector.jar" ]; then
    echo "💾 Backing up previous version..."
    BACKUP_NAME="backup-$(date +%Y%m%d-%H%M%S).jar"
    sudo cp "$APP_DIR/trade-collector.jar" "$APP_DIR/backups/$BACKUP_NAME"
fi

# 8. Останавливаем сервис
echo "🛑 Stopping service..."
sudo systemctl stop "$APP_NAME.service" 2>/dev/null || true

# 9. Копируем файлы
echo "📄 Copying files..."
sudo cp -v trade-collector.jar config.json "$APP_DIR/"
if [ -f "trade-collector.service" ]; then
    sudo cp -v trade-collector.service run.sh init-database.sh "$APP_DIR/"
fi

# 10. Создаем environment файл
echo "🔒 Creating environment file..."
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

sudo chown root:deploy /etc/default/trade-collector
sudo chmod 640 /etc/default/trade-collector

# 11. Устанавливаем права
echo "🔐 Setting permissions..."
sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR" "/var/log/$APP_NAME"
sudo chmod 755 "$APP_DIR/run.sh" "$APP_DIR/init-database.sh"
sudo chmod 644 "$APP_DIR/trade-collector.jar" "$APP_DIR/config.json" 2>/dev/null || true

# 12. Настраиваем systemd сервис
echo "⚙️ Configuring systemd service..."
if [ -f "$APP_DIR/trade-collector.service" ]; then
    sudo cp "$APP_DIR/trade-collector.service" /etc/systemd/system/
    echo "✅ Service file copied to /etc/systemd/system/"
else
    echo "❌ ERROR: trade-collector.service not found!"
    exit 1
fi

# 13. Инициализируем базу данных
if [ -n "$DB_PASSWORD" ] && [ -f "$APP_DIR/init-database.sh" ]; then
    echo "🗄️ Initializing database..."
    sudo chmod +x "$APP_DIR/init-database.sh"
    cd "$APP_DIR"
    export DB_PASSWORD DB_HOST DB_PORT DB_USER DB_NAME
    sudo -u deploy ./init-database.sh
fi

# 14. Перезагружаем и запускаем сервис
echo "🔄 Reloading systemd..."
sudo systemctl daemon-reload
sudo systemctl enable "$APP_NAME.service"

echo "🚀 Starting service..."
sudo systemctl start "$APP_NAME.service"

# 15. Проверяем статус
echo "📊 Checking service status..."
sleep 3
sudo systemctl status "$APP_NAME.service" --no-pager -l

# 16. Очистка
cd /
rm -rf "$TEMP_DIR"

echo "=== DEPLOYMENT COMPLETED ==="