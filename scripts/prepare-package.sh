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

# Копируем скрипты
echo "📄 Copying scripts..."
cp scripts/trade-collector.service deploy-package/
cp scripts/run.sh deploy-package/
cp scripts/deploy-remote.sh deploy-package/
cp scripts/init-database.sh deploy-package/

# Копируем SQL схему
if [ -f "scripts/001_init_schema.sql" ]; then
    cp scripts/001_init_schema.sql deploy-package/
    echo "✅ SQL schema copied"
fi

# Копируем config.json БЕЗ изменений
echo "📄 Copying config.json..."
if [ -f "config.json" ]; then
    cp config.json deploy-package/
    echo "✅ config.json copied (original)"
else
    echo "❌ ERROR: config.json not found!"
    exit 1
fi

echo "✅ Package prepared successfully!"
ls -la deploy-package/