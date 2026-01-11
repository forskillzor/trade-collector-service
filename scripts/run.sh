#!/bin/bash
set -e

cd /opt/trade-collector

echo "🚀 Starting Trade Collector Service..."
echo "📄 Using config.json"

# Systemd автоматически загружает EnvironmentFile
# Дополнительная проверка
if [ -z "$DB_PASSWORD" ]; then
    echo "❌ ERROR: DB_PASSWORD is not set!"
    echo "Please check /etc/default/trade-collector file"
    exit 1
fi

echo "✅ Database configured: ${DB_HOST:-localhost}:${DB_PORT:-6432}/${DB_NAME:-trade_collector}"

# JVM параметры
JVM_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -Xmx512m"
JVM_OPTS="$JVM_OPTS -Xms256m"

echo ""

# Запуск приложения
exec java $JVM_OPTS -jar trade-collector.jar --config "config.json"