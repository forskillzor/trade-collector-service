#!/bin/bash
set -e

# Проверяем аргументы
if [ $# -lt 1 ]; then
    echo "❌ Usage: $0 <release_tag>"
    echo "   Example: $0 v1.0.0"
    exit 1
fi

RELEASE_TAG="$1"
echo "🚀 Starting deployment of $RELEASE_TAG to $VPS_HOST..."

# Настройка SSH
SSH_KEY_FILE="$HOME/.ssh/vps_key"
mkdir -p ~/.ssh
echo "$VPS_SSH_KEY" | tr -d '\r' > "$SSH_KEY_FILE"
chmod 600 "$SSH_KEY_FILE"
ssh-keyscan -H "$VPS_HOST" >> ~/.ssh/known_hosts 2>/dev/null

# Выполняем удаленный деплой
ssh -i "$SSH_KEY_FILE" "$VPS_USER@$VPS_HOST" "
  # Экспортируем переменные
  export DB_PASSWORD='$DB_PASSWORD'
  export DB_HOST='$DB_HOST'
  export DB_PORT='$DB_PORT'
  export DB_USER='$DB_USER'
  export DB_NAME='$DB_NAME'
  export RELEASE_TAG='$RELEASE_TAG'

  echo '📦 Starting remote deployment...'
  echo '📊 Version: '\$RELEASE_TAG
  echo '🏠 Database: '\$DB_HOST:\$DB_PORT

  # Скачиваем и запускаем скрипт деплоя
  curl -sL https://raw.githubusercontent.com/forskillzor/TradeCollectorService/master/scripts/deploy-remote.sh | bash -s -- '$RELEASE_TAG'
"

echo "✅ Deployment of $RELEASE_TAG initiated!"