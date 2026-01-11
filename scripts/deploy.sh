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

# Копируем файлы
echo "📦 Copying files to server..."
scp -i "$SSH_KEY_FILE" -r deploy-package/* "$VPS_USER@$VPS_HOST:/tmp/deploy/"

# Выполняем деплой
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
  chmod +x deploy-remote.sh
  ./deploy-remote.sh
"

echo "✅ Deployment completed!"