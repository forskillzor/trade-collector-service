#!/bin/bash
set -e

echo "🔍 Checking server environment..."

ssh -i ~/.ssh/vps_key "$VPS_USER@$VPS_HOST" "
echo '=== System Information ==='
cat /etc/os-release
echo ''

echo '=== Java Information ==='
which java || echo 'Java not in PATH'
java -version 2>&1
echo ''

echo '=== Service Status ==='
sudo systemctl status trade-collector.service 2>/dev/null | head -10 || echo 'Service not found'
echo ''

echo '=== Disk Space ==='
df -h /opt
echo ''

echo '=== Memory ==='
free -h
echo ''

echo '=== Open Ports ==='
sudo netstat -tlnp | grep -E ':(80|443|8080|8443)' || true
echo ''

echo '=== UFW Status ==='
sudo ufw status 2>/dev/null || echo 'UFW not available'
"