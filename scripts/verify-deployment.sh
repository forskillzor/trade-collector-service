#!/bin/bash
set -e

if [ -z "$VPS_HOST" ]; then
    echo "❌ ERROR: VPS_HOST is not set"
    exit 1
fi

echo "🔍 Verifying deployment on $VPS_HOST..."

# Проверяем доступные endpoints
ENDPOINTS=("/health" "/actuator/health" "/" "/api/health" "/ping")
MAX_RETRIES=5
RETRY_DELAY=10

for attempt in $(seq 1 $MAX_RETRIES); do
    echo "Attempt $attempt/$MAX_RETRIES..."

    # Проверяем порт
    if nc -z -w 5 "$VPS_HOST" 8080 2>/dev/null; then
        echo "✅ Port 8080 is open"

        # Проверяем каждый endpoint
        for endpoint in "${ENDPOINTS[@]}"; do
            echo "  Trying $endpoint..."
            RESPONSE=$(curl -s -f --max-time 5 "http://$VPS_HOST:8080$endpoint" 2>/dev/null || true)

            if [ -n "$RESPONSE" ]; then
                echo "✅ Service is responding on $endpoint"
                echo "Response: $RESPONSE"
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
exit 1