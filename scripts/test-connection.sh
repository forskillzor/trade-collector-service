#!/bin/bash
set -e

VPS_HOST="95.81.99.28"
VPS_USER="deploy"

echo "🔍 Проверка подключения к серверу..."

# Проверяем SSH подключение
ssh $VPS_USER@$VPS_HOST "echo '✅ SSH подключение работает!'"

# Проверяем наличие Java
ssh $VPS_USER@$VPS_HOST "java --version && echo '✅ Java установлена'"

# Проверяем PostgreSQL
ssh $VPS_USER@$VPS_HOST "sudo systemctl status postgresql --no-pager | grep -E '(active|running)' && echo '✅ PostgreSQL работает'"

# Проверяем директории
ssh $VPS_USER@$VPS_HOST "
echo '📁 Проверка директорий:'
ls -la /opt/
ls -la /var/log/ | grep trade || echo '⚠️ Директории логов нет'
"

echo "✅ Все проверки пройдены!"