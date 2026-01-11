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

# Создаем config.json
echo "⚙️ Creating config.json..."
cat > deploy-package/config.json << 'EOF'
{
  "server": {
    "port": 8080,
    "host": "0.0.0.0"
  },
  "database": {
    "url": "jdbc:postgresql://localhost:5432/trade_collector",
    "user": "trade_user",
    "driver": "org.postgresql.Driver"
  },
  "logging": {
    "level": "INFO"
  }
}
EOF

# Если есть пароль БД, добавляем его
if [ -n "$DB_PASSWORD" ]; then
    echo "🔧 Adding database password to config..."
    sed -i '/"user": "trade_user",/a\    "password": "'"$DB_PASSWORD"'",' deploy-package/config.json
fi

echo "✅ Package prepared successfully!"
echo "📁 Package contents:"
ls -la deploy-package/