#!/bin/bash
set -e

# Проверяем переменные окружения
if [ -z "$VPS_HOST" ] || [ -z "$VPS_USER" ] || [ -z "$VPS_SSH_KEY" ]; then
    echo "❌ ERROR: Missing required environment variables"
    exit 1
fi

echo "🚀 Starting deployment to $VPS_HOST..."

# Создаем временный файл с ключом
SSH_KEY_FILE="$HOME/.ssh/vps_key"
mkdir -p ~/.ssh
echo "$VPS_SSH_KEY" > "$SSH_KEY_FILE"
chmod 600 "$SSH_KEY_FILE"

# Добавляем хост в known_hosts
ssh-keyscan -H "$VPS_HOST" >> ~/.ssh/known_hosts 2>/dev/null

# Копируем файлы на сервер
echo "📦 Copying files to server..."
scp -i "$SSH_KEY_FILE" -r deploy-package/* "$VPS_USER@$VPS_HOST:/tmp/deploy/"

# Копируем deploy-remote.sh на сервер
echo "📄 Copying deployment script..."
scp -i "$SSH_KEY_FILE" scripts/deploy-remote.sh "$VPS_USER@$VPS_HOST:/tmp/"

# Выполняем деплой
echo "🔄 Executing deployment script on server..."
ssh -i "$SSH_KEY_FILE" "$VPS_USER@$VPS_HOST" "
  chmod +x /tmp/deploy-remote.sh
  /tmp/deploy-remote.sh
"

echo "✅ Deployment completed!"