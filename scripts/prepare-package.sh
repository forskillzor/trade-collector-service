#!/bin/bash
set -e

echo "📦 Preparing deployment package..."

# Создаем директорию
rm -rf deploy-package
mkdir -p deploy-package

# 1. Находим JAR файл
JAR_FILE=$(find build/libs -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" | head -1)

if [ -z "$JAR_FILE" ]; then
    echo "❌ ERROR: No JAR file found in build/libs/"
    echo "Searching in build/libs/:"
    ls -la build/libs/ 2>/dev/null || echo "build/libs/ directory doesn't exist"
    exit 1
fi

echo "Using JAR file: $JAR_FILE (size: $(stat -c%s "$JAR_FILE") bytes)"

# 2. Копируем JAR
cp "$JAR_FILE" deploy-package/trade-collector.jar
echo "✅ JAR copied"

# 3. Копируем продакшен-конфиг
echo "📄 Copying config/config.prod.json..."
mkdir -p deploy-package/config
if [ -f "config/config.prod.json" ]; then
    cp config/config.prod.json deploy-package/config/config.prod.json
    echo "✅ config/config.prod.json → deploy-package/config/config.prod.json"
elif [ -f "config/production.json" ]; then
    cp config/production.json deploy-package/config/config.prod.json
    echo "✅ config/production.json → deploy-package/config/config.prod.json (legacy)"
else
    echo "❌ ERROR: No production config found!"
    exit 1
fi

# 4. Копируем ОБЯЗАТЕЛЬНЫЕ скрипты для сервера
echo "📄 Copying server scripts..."
MANDATORY_SCRIPTS=("trade-collector.service" "run.sh" "init-database.sh" "deploy-remote.sh" "backup-db.sh" "verify-deployment.sh" "tune-postgres.sh")

for script in "${MANDATORY_SCRIPTS[@]}"; do
    if [ -f "scripts/$script" ]; then
        cp "scripts/$script" deploy-package/
        echo "✅ $script copied"
    else
        echo "❌ ERROR: Required script $script not found!"
        exit 1
    fi
done

# 5. Копируем ОПЦИОНАЛЬНЫЕ файлы
echo "📄 Copying optional files..."

# SQL схема (если есть)
if [ -f "sql/001_init_schema.sql" ]; then
    cp "sql/001_init_schema.sql" deploy-package/
    echo "✅ SQL schema copied"
fi

# reset-database.sh (только для ручного сброса БД на VPS, не запускается автоматически)
if [ -f "scripts/reset-database.sh" ]; then
    cp "scripts/reset-database.sh" deploy-package/
    echo "✅ reset-database.sh copied (manual use only)"
fi

# install-netdata.sh (опциональный мониторинг)
if [ -f "scripts/install-netdata.sh" ]; then
    cp "scripts/install-netdata.sh" deploy-package/
    echo "✅ install-netdata.sh copied"
fi

# 6. Создаем файл README с инструкцией
cat > deploy-package/README.md << 'EOF'
# Trade Collector Deployment Package

## Содержимое:
- `trade-collector.jar` - основное приложение
- `config.json` - конфигурация приложения
- `trade-collector.service` - systemd unit file
- `run.sh` - скрипт запуска
- `init-database.sh` - инициализация БД

## Для деплоя на сервер:
1. Скопируйте все файлы в `/opt/trade-collector/`
2. Установите права: `chmod +x /opt/trade-collector/*.sh`
3. Скопируйте service файл: `sudo cp trade-collector.service /etc/systemd/system/`
4. Настройте переменные окружения в `/etc/default/trade-collector`
5. Запустите: `sudo systemctl start trade-collector`
EOF

echo "✅ README.md created"

# 7. Итог
echo ""
echo "✅ Package prepared successfully!"
echo "📁 Contents of deploy-package/:"
ls -la deploy-package/
echo ""
echo "📦 Total size: $(du -sh deploy-package/ | cut -f1)"

# В самом конце prepare-package.sh
TAG=${1:-"$(date +%Y%m%d-%H%M%S)"}
TAR_NAME="trade-collector-${TAG}.tar.gz"
tar -czf "$TAR_NAME" -C deploy-package .
echo "✅ Deployment archive created: $TAR_NAME"