#!/bin/bash
set -e

cd /opt/trade-collector

echo "🚀 Starting Trade Collector Service..."
echo "Java version:"
java -version 2>&1
echo ""

# JVM параметры
JVM_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -Xmx512m"
JVM_OPTS="$JVM_OPTS -Xms256m"
JVM_OPTS="$JVM_OPTS -XX:MaxRAMPercentage=75.0"

echo "Using JVM options: $JVM_OPTS"
echo ""

# Запуск приложения
exec java $JVM_OPTS -jar trade-collector.jar --config config.json