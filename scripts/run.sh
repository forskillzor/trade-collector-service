#!/bin/bash
set -e

cd /opt/trade-collector

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

# Проверяем наличие PostgreSQL драйвера
POSTGRES_DRIVER="postgresql.jar"
if [ ! -f "$POSTGRES_DRIVER" ]; then
    echo "📦 PostgreSQL driver not found. Downloading..."
    wget -q -O "$POSTGRES_DRIVER" \
        https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.3/postgresql-42.7.3.jar

    if [ ! -f "$POSTGRES_DRIVER" ]; then
        echo "❌ ERROR: Failed to download PostgreSQL driver!"
        exit 1
    fi
    echo "✅ PostgreSQL driver downloaded"
else
    echo "✅ PostgreSQL driver found ($(ls -lh "$POSTGRES_DRIVER" | awk '{print $5}'))"
fi

# Проверяем JAR файл
if [ ! -f "trade-collector.jar" ]; then
    echo "❌ ERROR: trade-collector.jar not found!"
    exit 1
fi

# JVM параметры
JVM_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -Xmx512m"
JVM_OPTS="$JVM_OPTS -Xms256m"

echo ""
echo "🔧 Starting application with PostgreSQL driver..."

# ДВА ВАРИАНТА ЗАПУСКА:

# ВАРИАНТ 1: Используем -cp (classpath) вместо -jar
# Передаем переменные окружения в Java процесс
DB_PASSWORD="$DB_PASSWORD" DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" \
DB_USER="$DB_USER" DB_NAME="$DB_NAME" \
java $JVM_OPTS \
    -cp "trade-collector.jar:$POSTGRES_DRIVER" \
    com.aandios.MainKt

# ВАРИАНТ 2 (раскомментировать если вариант 1 не работает):
# Используем PropertiesLauncher для Spring Boot приложений
# java $JVM_OPTS \
#     -Dloader.path="$POSTGRES_DRIVER" \
#     -Dloader.main=com.aandios.MainKt \
#     -jar trade-collector.jar

# ВАРИАНТ 3 (раскомментировать если варианты 1 и 2 не работают):
# Просто -jar (если драйвер уже внутри JAR)
# java $JVM_OPTS -jar trade-collector.jar