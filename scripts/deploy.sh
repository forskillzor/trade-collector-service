#!/bin/bash
set -e

# Проверяем обязательные переменные
if [ -z "$VPS_HOST" ] || [ -z "$VPS_USER" ] || [ -z "$VPS_SSH_KEY" ]; then
    echo "❌ ERROR: Missing required environment variables"
    exit 1
fi

echo "🚀 Starting deployment to $VPS_HOST..."

# Создаем временный файл с ключом
SSH_KEY_FILE="$HOME/.ssh/vps_key"
mkdir -p ~/.ssh
echo "$VPS_SSH_KEY" | tr -d '\r' > "$SSH_KEY_FILE"
chmod 600 "$SSH_KEY_FILE"

# Добавляем хост в known_hosts
ssh-keyscan -H "$VPS_HOST" >> ~/.ssh/known_hosts 2>/dev/null

# Копируем файлы на сервер
echo "📦 Copying files to server..."
scp -i "$SSH_KEY_FILE" -r deploy-package/* "$VPS_USER@$VPS_HOST:/tmp/deploy/"

# Выполняем деплой с передачей ВСЕХ переменных
echo "🔄 Executing deployment script on server..."
ssh -i "$SSH_KEY_FILE" "$VPS_USER@$VPS_HOST" "
  # Экспортируем все переменные окружения
  export DB_PASSWORD='$DB_PASSWORD'
  export DB_HOST='$DB_HOST'
  export DB_PORT='$DB_PORT'
  export DB_USER='$DB_USER'
  export DB_NAME='$DB_NAME'
  export VPS_HOST='$VPS_HOST'
  export VPS_USER='$VPS_USER'

  chmod +x /tmp/deploy-remote.sh
  /tmp/deploy-remote.sh
"
# Создаем config с подстановкой переменных
cat > "$APP_DIR/config-runtime.json" << 'EOF'
{
  "database": {
    "host": "${DB_HOST}",
    "port": ${DB_PORT},
    "database": "${DB_NAME}",
    "username": "${DB_USER}",
    "password": "${DB_PASSWORD}"
  }
}
EOF

# Подставляем переменные
envsubst < "$APP_DIR/config-runtime.json" > "$APP_DIR/config.json"

echo "✅ Deployment completed!"