#!/usr/bin/env bash
# tPlanner Sync V3 installer (Raspberry Pi / Debian family)
# 用法: sudo bash sync-server/deploy/install.sh
# 统一使用专用 tplanner 用户;数据放 /var/lib/tplanner-sync,不放 /home/hamhuo/Documents。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_DIR=/opt/tplanner-sync
DATA_DIR=/var/lib/tplanner-sync
USER_NAME=tplanner
HAD_INSTALL=0

if [ "$(readlink -f "$REPO_DIR")" = "$(readlink -f "$APP_DIR")" ]; then
    echo "run this installer from a separate reviewed staging checkout, not $APP_DIR" >&2
    exit 1
fi

if [[ $EUID -ne 0 ]]; then
    echo "must run as root: sudo bash sync-server/deploy/install.sh" >&2
    exit 1
fi

for required in /usr/bin/node npm openssl tar curl systemctl; do
    if ! command -v "$required" >/dev/null 2>&1; then
        echo "missing required executable: $required" >&2
        exit 1
    fi
done
# package.json engines 要求 Node >= 20:只检查"存在"不够,版本过低同样拒绝。
NODE_MAJOR="$(/usr/bin/node -p 'process.versions.node.split(".")[0]' 2>/dev/null || printf '0')"
if [ "$NODE_MAJOR" -lt 20 ]; then
    echo "Node.js >= 20 required; found: $(/usr/bin/node --version 2>/dev/null || printf 'no node')" >&2
    exit 1
fi
if [ ! -x "$APP_DIR/nats-server" ]; then
    echo "missing pinned NATS binary: $APP_DIR/nats-server" >&2
    exit 1
fi

# 1. 专用服务用户(不登录、无 shell)
if ! id -u "$USER_NAME" >/dev/null 2>&1; then
    useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin "$USER_NAME"
fi

# 2. 数据目录(建议把 DATA_DIR 挂到 SSD)
install -d -o "$USER_NAME" -g "$USER_NAME" \
    "$DATA_DIR"/state "$DATA_DIR"/jetstream "$DATA_DIR"/backups
if [ -d "$APP_DIR/sync-server" ]; then
    HAD_INSTALL=1
fi
if [ "$HAD_INSTALL" -eq 1 ] && [ ! -s "$DATA_DIR/state/tplanner.db" ]; then
    echo "existing installation has no non-empty SQLite database; refusing destructive replacement" >&2
    exit 1
fi

# 3. 应用目录:只部署 sync-server 与 sync-v3 协议,不带 .git 与桌面端代码。
# 保留既有 NATS 密钥，删除旧目录确保已退役的 V1 文件不会残留。
install -d "$APP_DIR"
OLD_NATS_CREDS="$(mktemp)"
trap 'rm -f "$OLD_NATS_CREDS"' EXIT
if [ -f "$APP_DIR/sync-server/deploy/nats.creds" ]; then
    cp "$APP_DIR/sync-server/deploy/nats.creds" "$OLD_NATS_CREDS"
fi
systemctl stop \
    tplanner-sync-healthcheck.timer \
    tplanner-sync-backup.timer \
    tplanner-sync-healthcheck.service \
    tplanner-sync-backup.service \
    tplanner-sync-api.service \
    tplanner-state-builder.service 2>/dev/null || true
systemctl stop nats-server.service 2>/dev/null || true
# The pre-V3 all-in-one service must never be restarted after this release.
systemctl disable --now tplanner-sync.service 2>/dev/null || true
rm -f /etc/systemd/system/tplanner-sync.service
rm -rf -- "$APP_DIR/sync-server" "$APP_DIR/sync-v3"
cp -r "$REPO_DIR/sync-server" "$APP_DIR/"
cp -r "$REPO_DIR/sync-v3" "$APP_DIR/"
chmod 750 "$APP_DIR"/sync-server/deploy/*.sh
chown -R "$USER_NAME":"$USER_NAME" "$APP_DIR" "$DATA_DIR"

# 4. NATS 凭据:替换配置占位符,另存一份 600 权限的 creds 供应用读取
NATS_CONF="$APP_DIR/sync-server/deploy/nats-server.conf"
NATS_CREDS="$APP_DIR/sync-server/deploy/nats.creds"
# openssl rand 输出有限,避免 tr|head 的 SIGPIPE 在 pipefail 下杀死脚本
if [ -s "$OLD_NATS_CREDS" ]; then
    NATS_PASS="$(cut -d: -f2- "$OLD_NATS_CREDS")"
else
    NATS_PASS="$(openssl rand -hex 16)"
fi
sed -i "s/__TPLANNER_PASSWORD_PLACEHOLDER__/$NATS_PASS/" "$NATS_CONF"
if grep -q '__TPLANNER_PASSWORD_PLACEHOLDER__' "$NATS_CONF"; then
    echo "failed to install NATS credential" >&2
    exit 1
fi
umask 077
printf 'tplanner:%s\n' "$NATS_PASS" > "$NATS_CREDS"
chown "$USER_NAME":"$USER_NAME" "$NATS_CREDS"
rm -f "$OLD_NATS_CREDS"
trap - EXIT

# 5. 安装生产依赖。服务仍然保持停止；先生成迁移前恢复点，再等待旧
# builder lease 失效并执行幂等 canonical task migration。
sudo -u "$USER_NAME" npm --prefix "$APP_DIR/sync-server" ci --omit=dev --cache "$DATA_DIR/.npm"
if [ -s "$DATA_DIR/state/tplanner.db" ]; then
    sudo -u "$USER_NAME" "$APP_DIR/sync-server/deploy/backup.sh"
else
    echo "fresh installation: no pre-migration database to back up"
fi
sudo -u "$USER_NAME" env \
    TPLANNER_DB_PATH="$DATA_DIR/state/tplanner.db" \
    TPLANNER_WAIT_FOR_LEASE_MS=35000 \
    /usr/bin/node "$APP_DIR/sync-server/scripts/migrate-canonical-task-fields.mjs"

# 6. 只有备份与迁移成功后才安装/启用 systemd 单元。
install -m 644 "$APP_DIR"/sync-server/deploy/*.service /etc/systemd/system/
install -m 644 "$APP_DIR"/sync-server/deploy/*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable \
    nats-server.service \
    tplanner-state-builder.service \
    tplanner-sync-api.service \
    tplanner-sync-healthcheck.timer \
    tplanner-sync-backup.timer

cat <<'EOF'
安装、迁移前备份与 canonical task migration 已完成。启动顺序:
  systemctl start nats-server.service
  systemctl start tplanner-state-builder.service
  systemctl start tplanner-sync-api.service
  systemctl start tplanner-sync-healthcheck.timer tplanner-sync-backup.timer
日志: journalctl -u tplanner-sync-api -f
EOF
