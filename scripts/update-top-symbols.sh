#!/bin/bash
# Скрипт для получения top N Perpetual символов Binance по дневному объёму
# Использование: ./scripts/update-top-symbols.sh [limit] [output_file]
# По умолчанию: 100 символов, вывод в stdout

LIMIT=${1:-100}
OUTPUT=${2:-}

SYMBOLS=$(curl -s "https://fapi.binance.com/fapi/v1/ticker/24hr" | \
  python3 -c "
import json, sys
data = json.load(sys.stdin)
perps = [t for t in data if t['symbol'].endswith('USDT')]
perps.sort(key=lambda t: float(t['quoteVolume']), reverse=True)
for t in perps[:$LIMIT]:
    print(t['symbol'].lower())
")

if [ -n "$OUTPUT" ]; then
    echo "Top $LIMIT symbols saved to $OUTPUT"
    echo "$SYMBOLS" > "$OUTPUT"
else
    echo "$SYMBOLS"
fi