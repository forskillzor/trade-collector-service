#!/bin/bash
set -e

# Проверяем обязательные переменные
if [ -z "$VPS_HOST" ] || [ -z "$VPS_USER" ] || [ -z "$VPS_SSH_KEY" ]; then
    echo "❌ ERROR: Missing required environment variables"
    exit 1
fi

echo "🚀 Starting deployment to $VPS_HOST..."

# Настройка SSH
SSH_KEY_FILE="$HOME/.ssh/vps_key"
mkdir -p ~/.ssh
echo "$VPS_SSH_KEY" | tr -d '\r' > "$SSH_KEY_FILE"
chmod 600 "$SSH_KEY_FILE"
ssh-keyscan -H "$VPS_HOST" >> ~/.ssh/known_hosts 2>/dev/null

# 1. Сначала очистим старый deploy на сервере
echo "🧹 Cleaning old deployment on server..."
ssh -i "$SSH_KEY_FILE" "$VPS_USER@$VPS_HOST" "rm -rf /tmp/deploy"

# 2. Копируем ВСЕ необходимые файлы
echo "📦 Copying ALL deployment files to server..."

# Создаем временную директорию со ВСЕМИ файлами
mkdir -p /tmp/full-deploy
cp -r deploy-package/* /tmp/full-deploy/
cp scripts/deploy-remote.sh /tmp/full-deploy/
cp scripts/init-database.sh /tmp/full-deploy/
cp scripts/run.sh /tmp/full-deploy/
cp scripts/trade-collector.service /tmp/full-deploy/

# Копируем на сервер
scp -i "$SSH_KEY_FILE" -r /tmp/full-deploy/* "$VPS_USER@$VPS_HOST:/tmp/deploy/"

# 3. Выполняем деплой
echo "🔄 Executing deployment script on server..."
ssh -i "$SSH_KEY_FILE" "$VPS_USER@$VPS_HOST" "
  # Экспортируем переменные
  export DB_PASSWORD='$DB_PASSWORD'
  export DB_HOST='$DB_HOST'
  export DB_PORT='$DB_PORT'
  export DB_USER='$DB_USER'
  export DB_NAME='$DB_NAME'

  # Запускаем деплой
  cd /tmp/deploy
  ls -la  # Покажем что скопировалось
  chmod +x deploy-remote.sh init-database.sh run.sh
  ./deploy-remote.sh
"

echo "✅ Deployment completed!"