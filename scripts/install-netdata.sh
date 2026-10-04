#!/bin/bash
# Install Netdata monitoring for single VPS (lightweight: ~60MB RAM)
# Run: sudo bash install-netdata.sh
set -e

echo "📊 Installing Netdata..."

if systemctl is-active --quiet netdata 2>/dev/null; then
    echo "✅ Netdata already running"
    systemctl status netdata --no-pager | head -5
    exit 0
fi

wget -q -O /tmp/netdata-kickstart.sh https://get.netdata.cloud/kickstart.sh
bash /tmp/netdata-kickstart.sh \
    --no-updates \
    --disable-telemetry \
    --claim-token "" \
    --claim-rooms "" \
    --dont-wait

# Lock down to localhost
if [ -f /etc/netdata/netdata.conf ]; then
    sed -i 's/^#\? *bind to.*/bind to = 127.0.0.1/' /etc/netdata/netdata.conf
    systemctl restart netdata
fi

echo ""
echo "✅ Netdata installed"
echo "   Dashboard: http://localhost:19999"
echo "   Access remotely: ssh -L 19999:localhost:19999 user@vps"
echo "   Logs: sudo journalctl -u netdata -f"
