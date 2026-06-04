#!/bin/bash
set -e

cd /opt/trade-collector/current

echo "🚀 Starting Trade Collector Service..."

# Пробуем получить переменные разными способами:
# 1. Из аргументов командной строки
# 2. Из переменных окружения
# 3. Из файла /etc/default/trade-collector

# Если переменные не установлены, пробуем загрузить из файла
if [ -z "$DB_PASSWORD" ] && [ -f /etc/default/trade-collector ]; then
    echo "📋 Loading environment from /etc/default/trade-collector"
    # Используем grep чтобы безопасно извлечь переменные
    while IFS='=' read -r key value; do
        # Удаляем кавычки и пробелы
        key=$(echo "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        value=$(echo "$value" | sed "s/^['\"]//;s/['\"]$//;s/^[[:space:]]*//;s/[[:space:]]*$//")

        # Экспортируем только если ключ не пустой и не комментарий
        if [[ ! -z "$key" && ! "$key" =~ ^# ]]; then
            export "$key"="$value"
        fi
    done < <(grep -v '^#' /etc/default/trade-collector | grep '=')
fi

# Проверка переменных окружения
echo "📊 Environment variables:"
echo "  DB_HOST: ${DB_HOST:-not set}"
echo "  DB_PORT: ${DB_PORT:-not set}"
echo "  DB_NAME: ${DB_NAME:-not set}"
echo "  DB_USER: ${DB_USER:-not set}"
echo "  DB_PASSWORD: ${DB_PASSWORD:+******}"

if [ -z "$DB_PASSWORD" ]; then
    echo "❌ ERROR: DB_PASSWORD is not set!"
    echo "Sources checked:"
    echo "1. Environment variables: $(env | grep DB_ || echo 'none')"
    echo "2. File /etc/default/trade-collector: $(head -5 /etc/default/trade-collector 2>/dev/null || echo 'not found')"
    exit 1
fi

echo "✅ Database configured"

# PostgreSQL драйвер уже внутри shadowJar

# Проверяем JAR файл
if [ ! -f "trade-collector.jar" ]; then
    echo "❌ ERROR: trade-collector.jar not found!"
    exit 1
fi

# JVM параметры
JVM_OPTS="-XX:+UseG1GC -Xmx768m -Xms512m -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/trade-collector/logs/heapdump.hprof"

echo ""
echo "🚀 Starting application..."

exec java $JVM_OPTS -jar trade-collector.jar