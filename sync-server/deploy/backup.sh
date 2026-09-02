#!/usr/bin/env bash
# tPlanner Sync V3 在线备份入口。SQLite 通过 Online Backup API 生成一致副本，
# 校验后 gzip，并写 SHA-256 manifest；无需停止 API/State Builder。
# 路径可用环境变量覆盖(默认生产路径),便于 CI smoke test 在临时目录执行。
set -euo pipefail
umask 077

DATA_DIR="${TPLANNER_DATA_DIR:-/var/lib/tplanner-sync}"
BACKUP_DIR="${TPLANNER_BACKUP_DIR:-$DATA_DIR/backups}"
APP_ROOT="${TPLANNER_APP_ROOT:-/opt/tplanner-sync}"
APP_DIR="${TPLANNER_APP_DIR:-/opt/tplanner-sync/sync-server}"
NODE_BIN="${TPLANNER_NODE_BIN:-/usr/bin/node}"

install -d "$BACKUP_DIR"

TPLANNER_DB_PATH="$DATA_DIR/state/tplanner.db" \
TPLANNER_BACKUP_DIR="$BACKUP_DIR" \
    "$NODE_BIN" "$APP_DIR/scripts/backup.mjs"

# 配置体积很小，原子刷新 latest；凭据随包保存，权限由 umask 保证为私有。
# V4: cursor 签名密钥必须与 DB 一起恢复。
# 注意:GNU tar 不能向已压缩的 .tar.gz 追加成员(-r 对 gzip 归档无效),
# 因此必须在同一条 -czf 里一次性打包全部文件。
CONFIG_TMP="$BACKUP_DIR/.config-latest.tar.gz.tmp"
CURSOR_SECRET="$DATA_DIR/state/tplanner.db.cursor-secret"
if [ -f "$CURSOR_SECRET" ]; then
    tar -czf "$CONFIG_TMP" \
        -C "$APP_ROOT" \
        sync-server/deploy/nats-server.conf \
        sync-server/deploy/nats.creds \
        -C "$DATA_DIR/state" \
        tplanner.db.cursor-secret
else
    tar -czf "$CONFIG_TMP" \
        -C "$APP_ROOT" \
        sync-server/deploy/nats-server.conf \
        sync-server/deploy/nats.creds
fi
mv -f "$CONFIG_TMP" "$BACKUP_DIR/config-latest.tar.gz"

echo "verified backup written to $BACKUP_DIR"
