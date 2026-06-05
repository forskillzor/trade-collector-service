#!/bin/bash
# Trade Collector VPS Bootstrap
# Usage: curl -sL https://raw.githubusercontent.com/.../master/scripts/setup-vps.sh | bash -s -- v0.0.1
# Or:    bash setup-vps.sh v0.0.1
#
# Takes a bare Ubuntu 22.04/24.04 VPS and ends with trade-collector running.

set -e

RELEASE_TAG="${1:-}"
if [ -z "$RELEASE_TAG" ]; then
    echo "❌ Usage: $0 <version>"
    echo "   Example: $0 v0.0.1"
    exit 1
fi

ARCHIVE="trade-collector-${RELEASE_TAG}.tar.gz"
APP_DIR="/opt/trade-collector"
DEPLOY_USER="deploy"
DB_NAME="${DB_NAME:-trade_collector}"
DB_USER="${DB_USER:-trade_user}"
DB_PASSWORD="${DB_PASSWORD:-dev_password}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
GITHUB_REPO="${GITHUB_REPO:-forskillzor/TradeCollectorService}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()  { echo -e "${GREEN}[✓]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*"; exit 1; }

echo "═══════════════════════════════════════════════════════"
echo "  Trade Collector VPS Setup — $RELEASE_TAG"
echo "═══════════════════════════════════════════════════════"
echo ""

# ── Phase 1: OS detection ──────────────────────────────────────────
log "Phase 1: Detecting OS..."
if [ ! -f /etc/os-release ]; then
    err "Cannot detect OS (expected Ubuntu 22.04+)"
fi
source /etc/os-release
echo "  OS: $PRETTY_NAME"

if [ "$ID" != "ubuntu" ]; then
    err "This script requires Ubuntu (detected: $ID)"
fi

# ── Phase 2: System packages ──────────────────────────────────────
log "Phase 2: Installing system packages..."

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq

# Detect Java 21 availability
if ! java -version 2>/dev/null | grep -q "21\."; then
    log "Installing Java 21..."
    if apt-cache show openjdk-21-jre-headless >/dev/null 2>&1; then
        apt-get install -y -qq openjdk-21-jre-headless
    else
        # Ubuntu 22.04 fallback — add Temurin repo
        warn "Java 21 not in default repos, adding Eclipse Temurin..."
        apt-get install -y -qq wget apt-transport-https
        wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | \
            tee /etc/apt/keyrings/adoptium.asc >/dev/null
        echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | \
            tee /etc/apt/sources.list.d/adoptium.list >/dev/null
        apt-get update -qq
        apt-get install -y -qq temurin-21-jre
    fi
fi
log "Java: $(java -version 2>&1 | head -1)"

# ── Phase 3: PostgreSQL ───────────────────────────────────────────
log "Phase 3: Setting up PostgreSQL..."

if ! pg_isready -q 2>/dev/null; then
    if apt-cache show postgresql-16 >/dev/null 2>&1; then
        apt-get install -y -qq postgresql-16 postgresql-client-16
    elif apt-cache show postgresql >/dev/null 2>&1; then
        apt-get install -y -qq postgresql postgresql-client
    else
        err "PostgreSQL not available — install manually or use Docker"
    fi
fi

# Ensure PostgreSQL is running
systemctl enable --now postgresql 2>/dev/null || true
pg_isready -q || err "PostgreSQL not running after install"

log "PostgreSQL: $(psql --version 2>&1 | head -1)"

# ── Phase 4: Database role + database ─────────────────────────────
log "Phase 4: Creating database role and database..."

# Create role if not exists
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" 2>/dev/null | grep -q 1; then
    sudo -u postgres psql -c "CREATE ROLE $DB_USER WITH LOGIN PASSWORD '$DB_PASSWORD';" 2>/dev/null
    log "Created role: $DB_USER"
else
    # Update password in case it changed
    sudo -u postgres psql -c "ALTER ROLE $DB_USER WITH LOGIN PASSWORD '$DB_PASSWORD';" 2>/dev/null
    log "Role exists: $DB_USER (password updated)"
fi

# Create database if not exists
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" 2>/dev/null | grep -q 1; then
    sudo -u postgres psql -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;" 2>/dev/null
    log "Created database: $DB_NAME"
else
    log "Database exists: $DB_NAME"
fi

# Install uuid-ossp extension
sudo -u postgres psql -d "$DB_NAME" -c 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";' 2>/dev/null || true
log "Extension: uuid-ossp"

# ── Phase 5: pg_hba.conf — enable password auth ──────────────────
log "Phase 5: Configuring pg_hba.conf for password auth..."

PG_HBA=$(sudo -u postgres psql -tAc "SHOW hba_file;" 2>/dev/null) || PG_HBA=""
if [ -z "$PG_HBA" ]; then
    PG_HBA=$(find /etc/postgresql -name pg_hba.conf 2>/dev/null | head -1)
fi

if [ -n "$PG_HBA" ] && [ -f "$PG_HBA" ]; then
    # Add local md5 rule for trade_user if missing
    if ! grep -q "local.*$DB_NAME.*$DB_USER.*md5" "$PG_HBA"; then
        # Insert before first 'local all all' line
        sed -i "/^local\s\+all\s\+all/i local   $DB_NAME             $DB_USER                                md5" "$PG_HBA"
        log "Added local md5 auth for $DB_USER"
    fi
    # Add host md5 rule for trade_user on 127.0.0.1 if missing
    if ! grep -q "host.*$DB_NAME.*$DB_USER.*127.0.0.1.*md5" "$PG_HBA"; then
        echo "host    $DB_NAME             $DB_USER        127.0.0.1/32            md5" >> "$PG_HBA"
        log "Added host md5 auth for $DB_USER on 127.0.0.1"
    fi
    systemctl reload postgresql 2>/dev/null || systemctl restart postgresql 2>/dev/null
    sleep 1
    log "pg_hba.conf updated and reloaded"
else
    warn "pg_hba.conf not found — password auth may not work"
fi

# Verify connection
if PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" -c "SELECT 1" >/dev/null 2>&1; then
    log "DB connection verified"
else
    err "Cannot connect to PostgreSQL as $DB_USER — check pg_hba.conf"
fi

# ── Phase 6: Deploy user ──────────────────────────────────────────
log "Phase 6: Creating deploy user..."

if ! id "$DEPLOY_USER" &>/dev/null; then
    useradd -r -s /bin/bash -m -d "/home/$DEPLOY_USER" "$DEPLOY_USER"
    log "Created user: $DEPLOY_USER"
fi

# Passwordless sudo for deploy user
SUDOERS_FILE="/etc/sudoers.d/$DEPLOY_USER"
if [ ! -f "$SUDOERS_FILE" ]; then
    echo "$DEPLOY_USER ALL=(ALL) NOPASSWD: ALL" > "$SUDOERS_FILE"
    chmod 440 "$SUDOERS_FILE"
    log "Added $DEPLOY_USER to sudoers"
else
    log "Sudoers already configured"
fi

# ── Phase 7: App directory ────────────────────────────────────────
log "Phase 7: Setting up $APP_DIR..."

mkdir -p "$APP_DIR"/{releases,backups,logs}
chown -R "$(whoami):$(whoami)" "$APP_DIR"
log "Directory structure created"

# ── Phase 8: Config file ──────────────────────────────────────────
log "Phase 8: Creating /etc/default/trade-collector..."

cat > /etc/default/trade-collector << CFG
DB_HOST=$DB_HOST
DB_PORT=$DB_PORT
DB_NAME=$DB_NAME
DB_USER=$DB_USER
DB_PASSWORD=$DB_PASSWORD
CFG
chmod 644 /etc/default/trade-collector
log "Config created"

# ── Phase 9: Download and deploy ──────────────────────────────────
log "Phase 9: Downloading release $RELEASE_TAG..."

# Try GitHub API first, fall back to direct URL
GITHUB_TOKEN="${GITHUB_TOKEN:-}"
DOWNLOAD_URL=""

if [ -n "$GITHUB_TOKEN" ]; then
    ASSET_ID=$(curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" \
        "https://api.github.com/repos/$GITHUB_REPO/releases/tags/$RELEASE_TAG" \
        | python3 -c "import sys,json; assets=json.load(sys.stdin).get('assets',[]); print(next((a['id'] for a in assets if a['name']=='$ARCHIVE'), ''))" 2>/dev/null)
    if [ -n "$ASSET_ID" ]; then
        DOWNLOAD_URL="https://api.github.com/repos/$GITHUB_REPO/releases/assets/$ASSET_ID"
    fi
fi

if [ -z "$DOWNLOAD_URL" ]; then
    DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$RELEASE_TAG/$ARCHIVE"
    warn "No GITHUB_TOKEN, trying public download URL"
fi

echo "  Downloading: $ARCHIVE"
if [ -n "$GITHUB_TOKEN" ]; then
    curl -fsSL --retry 3 --retry-delay 5 \
        -H "Accept: application/octet-stream" \
        -H "Authorization: Bearer $GITHUB_TOKEN" \
        -o "/tmp/$ARCHIVE" "$DOWNLOAD_URL"
else
    curl -fsSL --retry 3 --retry-delay 5 \
        -o "/tmp/$ARCHIVE" "$DOWNLOAD_URL"
fi

if [ ! -s "/tmp/$ARCHIVE" ]; then
    err "Failed to download $ARCHIVE"
fi

# Validate archive
if ! tar -tzf "/tmp/$ARCHIVE" >/dev/null 2>&1; then
    echo "Archive first bytes: $(head -c 200 "/tmp/$ARCHIVE")"
    err "Downloaded file is not valid tar.gz"
fi
log "Archive downloaded ($(ls -lh /tmp/$ARCHIVE | awk '{print $5}'))"

# ── Phase 10: Deploy ──────────────────────────────────────────────
log "Phase 10: Running deploy-remote.sh..."

DEPLOY_DIR="/tmp/trade-collector-deploy-$(date +%Y%m%d%H%M%S)"
mkdir -p "$DEPLOY_DIR"
cd "$DEPLOY_DIR"
tar -xzf "/tmp/$ARCHIVE"

if [ ! -f "deploy-remote.sh" ]; then
    err "deploy-remote.sh not found in archive"
fi

chmod +x *.sh
export DB_PASSWORD DB_HOST DB_PORT DB_NAME DB_USER
./deploy-remote.sh "$RELEASE_TAG"

cd /
rm -rf "$DEPLOY_DIR"

# ── Phase 11: Verify ──────────────────────────────────────────────
log "Phase 11: Verifying..."

sleep 5
RETRIES=30
for i in $(seq 1 $RETRIES); do
    if curl -sf http://localhost:8080/health >/dev/null 2>&1; then
        log "Health check OK (attempt $i)"
        break
    fi
    if [ "$i" -eq "$RETRIES" ]; then
        warn "Health check failed after $RETRIES attempts"
        echo "Last 20 log lines:"
        journalctl -u trade-collector.service --no-pager -n 20 2>/dev/null || true
        echo ""
        echo "Service status:"
        systemctl status trade-collector.service --no-pager -l 2>/dev/null || true
        exit 1
    fi
    sleep 2
done

echo ""
echo "═══════════════════════════════════════════════════════"
echo "  ✅ Trade Collector $RELEASE_TAG is RUNNING"
echo "═══════════════════════════════════════════════════════"
echo ""
echo "  Health:  http://localhost:8080/health"
echo "  Status:  http://localhost:8080/status"
echo "  Logs:    journalctl -u trade-collector.service -f"
echo ""
