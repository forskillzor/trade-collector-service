#!/bin/bash
set -e

if [ -z "$VPS_HOST" ]; then
    echo "❌ ERROR: VPS_HOST is not set"
    exit 1
fi

echo "🔍 Verifying deployment on $VPS_HOST..."

# Проверяем, что сервис запущен
echo "🔍 Checking if trade-collector service is active..."
SERVICE_STATUS=$(systemctl is-active trade-collector 2>/dev/null || echo "unknown")

if [ "$SERVICE_STATUS" = "active" ]; then
    echo "✅ Service is running"
else
    echo "❌ Service is NOT running (status: $SERVICE_STATUS)"
    echo "📝 Last 20 lines of service logs:"
    journalctl -u trade-collector -n 20 --no-pager
    exit 1
fi

# Проверяем, были ли ошибки в логах за последние 5 минут
echo "🔍 Checking recent logs for errors..."
ERROR_COUNT=$(journalctl -u trade-collector --since "5 minutes ago" --priority err --quiet | wc -l)

if [ "$ERROR_COUNT" -gt 0 ]; then
    echo "⚠️  Found $ERROR_COUNT error(s) in logs in last 5 minutes"
    echo "📝 Last 10 lines of error logs:"
    journalctl -u trade-collector --since "5 minutes ago" -n 10 --priority err --no-pager
else
    echo "✅ No recent errors found in logs"
fi

# Проверяем доступные endpoints
ENDPOINTS=("/health" "/actuator/health" "/" "/api/health" "/ping")
MAX_RETRIES=10
RETRY_DELAY=10

for attempt in $(seq 1 $MAX_RETRIES); do
    echo "Attempt $attempt/$MAX_RETRIES..."

    # Проверяем порт
    if nc -z -w 5 "$VPS_HOST" 8080 2>/dev/null; then
        echo "✅ Port 8080 is open"

        # Проверяем каждый endpoint
        for endpoint in "${ENDPOINTS[@]}"; do
            echo "  Trying $endpoint..."
            RESPONSE=$(curl -s -f --max-time 10 "http://$VPS_HOST:8080$endpoint" 2>/dev/null || true)

            if [ -n "$RESPONSE" ]; then
                echo "✅ Service is responding on $endpoint"
                echo "Response preview: $(echo "$RESPONSE" | head -c 200)..."
                exit 0
            fi
        done
    else
        echo "❌ Port 8080 is not open"
    fi

    if [ $attempt -lt $MAX_RETRIES ]; then
        echo "Waiting $RETRY_DELAY seconds..."
        sleep $RETRY_DELAY
    fi
done

echo "❌ Service verification failed"
echo "📝 Final log status:"
journalctl -u trade-collector -n 20 --no-pager
exit 1