#!/bin/bash
set -e

echo "📦 Preparing deployment package..."

# Создаем директорию
mkdir -p deploy-package

# Находим JAR файл
JAR_FILE=$(find build/libs -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "❌ ERROR: No JAR file found in build/libs/"
    exit 1
fi

echo "Using JAR file: $JAR_FILE"
cp "$JAR_FILE" deploy-package/trade-collector.jar

# Копируем ВСЕ скрипты которые понадобятся на сервере
echo "📄 Copying scripts..."
cp scripts/trade-collector.service deploy-package/
cp scripts/run.sh deploy-package/
cp scripts/deploy-remote.sh deploy-package/  # ← добавляем этот файл

# Создаем config.json
echo "📄 Copying existing config.json..."
if [ -f "config.json" ]; then
    cp config.json deploy-package/
else
    echo "❌ ERROR: Real config.json not found! Please add it to project root"
    exit 1
fi

# Если есть пароль БД, добавляем его
if [ -n "$DB_PASSWORD" ]; then
    echo "🔧 Adding database password to config..."
    sed -i '/"user": "trade_user",/a\    "password": "'"$DB_PASSWORD"'",' deploy-package/config.json
fi

echo "✅ Package prepared successfully!"
echo "📁 Package contents:"
ls -la deploy-package/