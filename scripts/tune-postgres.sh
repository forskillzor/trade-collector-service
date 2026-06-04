#!/bin/bash
# PostgreSQL tuning for TradeCollectorService on 2GB VPS
# Run: sudo bash tune-postgres.sh
set -e

PG_CONF=$(sudo -u postgres psql -t -c "SHOW config_file" 2>/dev/null | tr -d ' ') || {
    PG_CONF="/etc/postgresql/16/main/postgresql.conf"
}

if [ ! -f "$PG_CONF" ]; then
    echo "❌ postgresql.conf not found at $PG_CONF"
    echo "Specify manually: PG_CONF=/path/to/postgresql.conf sudo bash $0"
    exit 1
fi

echo "Tuning PostgreSQL at $PG_CONF for 2GB VPS..."

sudo sed -i \
    -e 's/^#\?shared_buffers.*/shared_buffers = 256MB/' \
    -e 's/^#\?effective_cache_size.*/effective_cache_size = 768MB/' \
    -e 's/^#\?work_mem.*/work_mem = 16MB/' \
    -e 's/^#\?maintenance_work_mem.*/maintenance_work_mem = 64MB/' \
    -e 's/^#\?max_connections.*/max_connections = 30/' \
    -e 's/^#\?random_page_cost.*/random_page_cost = 1.1/' \
    "$PG_CONF"

echo "✅ PostgreSQL tuned. Restart with: sudo systemctl restart postgresql"
