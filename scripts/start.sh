#!/bin/bash
set -e

APP_NAME="trade-collector"
APP_DIR="/opt/$APP_NAME"
LOG_DIR="/var/log/$APP_NAME"
DATA_DIR="/var/lib/$APP_NAME"
PID_FILE="/var/run/$APP_NAME.pid"

# Создаем директории
mkdir -p $LOG_DIR $DATA_DIR/{aggregates,exports}

# Настройка systemd
cat > /etc/systemd/system/$APP_NAME.service << EOF
[Unit]
Description=Trade Collector Service
After=network.target postgresql.service
Requires=postgresql.service

[Service]
Type=simple
User=trader
Group=trader
WorkingDirectory=$APP_DIR
ExecStart=$APP_DIR/trade-collector
Restart=always
RestartSec=10
StandardOutput=append:$LOG_DIR/app.log
StandardError=append:$LOG_DIR/error.log
EnvironmentFile=$APP_DIR/.env
Environment="JAVA_OPTS=--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
PIDFile=$PID_FILE

# Security
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=$DATA_DIR

[Install]
WantedBy=multi-user.target
EOF

# Запуск сервиса
systemctl daemon-reload
systemctl enable $APP_NAME
systemctl restart $APP_NAME
systemctl status $APP_NAME