#!/bin/bash
set -e

cd /opt/trade-collector

echo "🚀 Starting Trade Collector Service..."
echo "📄 Config file: config.json"

# JVM параметры
JVM_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -Xmx512m"
JVM_OPTS="$JVM_OPTS -Xms256m"

echo ""
echo "📊 Environment:"
echo "  Database: ${DB_HOST:-localhost}:${DB_PORT:-6432}/${DB_NAME:-trade_collector}"
echo "  User: ${DB_USER:-trade_user}"
echo "  Java options: $JVM_OPTS"
echo ""

# Запуск приложения
exec java $JVM_OPTS -jar trade-collector.jar --config "config.json"