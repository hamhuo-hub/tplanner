#!/usr/bin/env bash
# tPlanner Sync V3 installer (Raspberry Pi / Debian family)
# 用法: sudo bash sync-server/deploy/install.sh
# 统一使用专用 tplanner 用户;数据放 /var/lib/tplanner-sync,不放 /home/hamhuo/Documents。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_DIR=/opt/tplanner-sync
DATA_DIR=/var/lib/tplanner-sync
USER_NAME=tplanner

if [[ $EUID -ne 0 ]]; then
    echo "must run as root: sudo bash sync-server/deploy/install.sh" >&2
    exit 1
fi

# 1. 专用服务用户(不登录、无 shell)
if ! id -u "$USER_NAME" >/dev/null 2>&1; then
    useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin "$USER_NAME"
fi

# 2. 数据目录(建议把 DATA_DIR 挂到 SSD)
install -d -o "$USER_NAME" -g "$USER_NAME" \
    "$DATA_DIR"/state "$DATA_DIR"/jetstream "$DATA_DIR"/backups

# 3. 应用目录:只部署 sync-server 与 sync-v3 协议,不带 .git 与桌面端代码
install -d "$APP_DIR"
cp -r "$REPO_DIR/sync-server" "$APP_DIR/"
cp -r "$REPO_DIR/sync-v3" "$APP_DIR/"
chown -R "$USER_NAME":"$USER_NAME" "$APP_DIR" "$DATA_DIR"

# 4. NATS 凭据:替换配置占位符,另存一份 600 权限的 creds 供应用读取
NATS_CONF="$APP_DIR/sync-server/deploy/nats-server.conf"
NATS_CREDS="$APP_DIR/sync-server/deploy/nats.creds"
# openssl rand 输出有限,避免 tr|head 的 SIGPIPE 在 pipefail 下杀死脚本
NATS_PASS="$(openssl rand -hex 16)"
sed -i "s/__TPlanner_PASSWORD_PLACEHOLDER__/$NATS_PASS/" "$NATS_CONF"
umask 077
printf 'tplanner:%s\n' "$NATS_PASS" > "$NATS_CREDS"
chown "$USER_NAME":"$USER_NAME" "$NATS_CREDS"

# 5. systemd 单元
install -m 644 "$APP_DIR"/sync-server/deploy/*.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable nats-server.service tplanner-state-builder.service tplanner-sync-api.service

cat <<'EOF'
安装完成。启动顺序:
  systemctl start nats-server.service
  systemctl start tplanner-state-builder.service
  systemctl start tplanner-sync-api.service
日志: journalctl -u tplanner-sync-api -f
EOF
