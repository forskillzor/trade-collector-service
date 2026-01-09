#!/bin/bash
# Настройка Prometheus + Grafana + Loki на VPS

# Prometheus config для сбора метрик
cat > /etc/prometheus/prometheus.yml << EOF
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'trade-collector'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/metrics'
EOF

# Logrotate config
cat > /etc/logrotate.d/trade-collector << EOF
/var/log/trade-collector/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 640 trader trader
    sharedscripts
    postrotate
        systemctl reload trade-collector >/dev/null 2>&1 || true
    endscript
}
EOF