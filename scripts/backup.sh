#!/bin/bash
# Бэкап данных PostgreSQL
BACKUP_DIR="/var/backups/trade-collector"
DATE=$(date +%Y%m%d_%H%M%S)

mkdir -p $BACKUP_DIR

# Бэкап БД
pg_dump -U trade_user -d trade_collector > $BACKUP_DIR/db_$DATE.sql
gzip $BACKUP_DIR/db_$DATE.sql

# Бэкап агрегированных данных
tar -czf $BACKUP_DIR/aggregates_$DATE.tar.gz /var/lib/trade-collector/aggregates

# Удаляем старые бэкапы (храним 30 дней)
find $BACKUP_DIR -type f -mtime +30 -delete

# Репликация на S3 (опционально)
# aws s3 sync $BACKUP_DIR s3://your-bucket/trade-collector-backups/