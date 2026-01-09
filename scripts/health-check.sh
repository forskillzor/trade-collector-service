#!/bin/bash
set -e

APP_NAME="trade-collector"
SERVICE_NAME="$APP_NAME"
HEALTH_URL="http://localhost:8080/health"
TIMEOUT=10
RETRY_INTERVAL=2
MAX_RETRIES=5

echo "🏥 Проверка здоровья $APP_NAME..."

# 1. Проверка systemd сервиса
if ! systemctl is-active --quiet $SERVICE_NAME; then
    echo "❌ Сервис $SERVICE_NAME не запущен"
    exit 1
fi
echo "✅ Systemd сервис активен"

# 2. Проверка процесса
PID=$(systemctl show -p MainPID $SERVICE_NAME | cut -d= -f2)
if [ "$PID" -le 1 ]; then
    echo "❌ Не удалось получить PID сервиса"
    exit 1
fi
echo "✅ PID процесса: $PID"

# 3. Проверка порта
if ! ss -tuln | grep -q ":8080 "; then
    echo "❌ Порт 8080 не слушается"
    exit 1
fi
echo "✅ Порт 8080 слушается"

# 4. Проверка HTTP здоровья с повторными попытками
for i in $(seq 1 $MAX_RETRIES); do
    echo "🔍 Попытка $i/$MAX_RETRIES: Проверка $HEALTH_URL..."

    if curl -f -s --max-time $TIMEOUT $HEALTH_URL > /dev/null; then
        echo "✅ HTTP health check пройден"

        # Дополнительная проверка метрик
        STATUS_RESPONSE=$(curl -s http://localhost:8080/status)
        if echo "$STATUS_RESPONSE" | grep -q '"status":"healthy"'; then
            echo "✅ Статус сервиса: healthy"

            # Проверка метрик
            TRADES_COUNT=$(echo "$STATUS_RESPONSE" | grep -o '"totalTrades":[0-9]*' | cut -d: -f2)
            if [ ! -z "$TRADES_COUNT" ]; then
                echo "📊 Обработано сделок: $TRADES_COUNT"
            fi

            exit 0
        fi
    fi

    if [ $i -lt $MAX_RETRIES ]; then
        echo "⏳ Ожидание $RETRY_INTERVAL сек перед следующей попыткой..."
        sleep $RETRY_INTERVAL
    fi
done

echo "❌ Health check не пройден после $MAX_RETRIES попыток"
exit 1