#!/bin/bash
set -e

# Загрузка переменных окружения
if [ -f .deploy.env ]; then
    source .deploy.env
else
    echo "❌ .deploy.env не найден"
    exit 1
fi

APP_NAME="trade-collector"
REMOTE_DIR="/opt/$APP_NAME"

echo "🚀 Деплой $APP_NAME на $VPS_HOST"

# 1. Сборка native image
echo "📦 Сборка native image..."
./gradlew nativeCompile

# 2. Копирование на VPS
echo "📤 Копирование на VPS..."
scp -i "$VPS_SSH_KEY" -r \
    build/native/nativeCompile/trade-collector \
    config/production.json \
    scripts/{start.sh,stop.sh,health-check.sh,restart.sh,update.sh} \
    # shellcheck disable=SC2086
    "$VPS_USER"@$VPS_HOST:/tmp/

# 3. Выполнение на VPS
echo "⚙️  Настройка на VPS..."
ssh -i "$VPS_SSH_KEY" "$VPS_USER"@"$VPS_HOST" << 'EOF'
set -e

APP_NAME="trade-collector"
APP_USER="trader"
APP_DIR="/opt/$APP_NAME"

# Создание пользователя если не существует
if ! id "$APP_USER" &>/dev/null; then
    echo "👤 Создание пользователя $APP_USER..."
    sudo useradd -m -s /bin/bash -r $APP_USER
    echo "✅ Пользователь $APP_USER создан"
fi

# Создание директорий
sudo mkdir -p $APP_DIR
sudo mkdir -p /var/log/$APP_NAME
sudo mkdir -p /var/lib/$APP_NAME/{aggregates,exports}
sudo mkdir -p /var/backups/$APP_NAME

# Копирование файлов
sudo cp /tmp/trade-collector $APP_DIR/
sudo cp /tmp/production.json $APP_DIR/config.json
sudo cp /tmp/*.sh $APP_DIR/

# Настройка прав
sudo chmod +x $APP_DIR/trade-collector $APP_DIR/*.sh
sudo chown -R $APP_USER:$APP_USER $APP_DIR /var/log/$APP_NAME /var/lib/$APP_NAME
sudo chown root:root $APP_DIR/*.sh
sudo chmod 755 $APP_DIR/*.sh

# Запуск сервиса
echo "🚀 Запуск сервиса..."
sudo $APP_DIR/start.sh

# Проверка
echo "🔍 Проверка запуска..."
sleep 5
sudo $APP_DIR/health-check.sh || echo "⚠️ Health check warning"

EOF

echo "✅ Деплой завершен!"