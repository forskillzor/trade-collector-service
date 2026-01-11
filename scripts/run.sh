#!/bin/bash
set -e

cd /opt/trade-collector

echo "🚀 Starting Trade Collector Service..."

# Загружаем .env файл если существует
if [ -f ".env" ]; then
    echo "📁 Loading environment variables from .env"
    set -a
    source .env
    set +a
fi

# Подставляем переменные в config.json если есть envsubst
if command -v envsubst >/dev/null 2>&1; then
    echo "🔧 Processing config.json with environment variables..."
    envsubst < config.json > config-runtime.json
    CONFIG_FILE="config-runtime.json"
else
    CONFIG_FILE="config.json"
    echo "⚠️ envsubst not found, using raw config.json"
fi

echo "Using config: $CONFIG_FILE"
echo "Database: ${DB_HOST:-localhost}:${DB_PORT:-6432}/${DB_NAME:-trade_collector}"

# JVM параметры
JVM_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -Xmx512m"
JVM_OPTS="$JVM_OPTS -Xms256m"
JVM_OPTS="$JVM_OPTS -XX:MaxRAMPercentage=75.0"

echo ""
echo "Using JVM options: $JVM_OPTS"
echo ""

# Запуск приложения
exec java $JVM_OPTS -jar trade-collector.jar --config "$CONFIG_FILE"