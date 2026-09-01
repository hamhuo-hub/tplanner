#!/usr/bin/env bash
# tPlanner Sync V3 在线备份入口。SQLite 通过 Online Backup API 生成一致副本，
# 校验后 gzip，并写 SHA-256 manifest；无需停止 API/State Builder。
set -euo pipefail
umask 077

DATA_DIR=/var/lib/tplanner-sync
BACKUP_DIR="$DATA_DIR/backups"
APP_DIR=/opt/tplanner-sync/sync-server

install -d "$BACKUP_DIR"

TPLANNER_DB_PATH="$DATA_DIR/state/tplanner.db" \
TPLANNER_BACKUP_DIR="$BACKUP_DIR" \
    /usr/bin/node "$APP_DIR/scripts/backup.mjs"

# 配置体积很小，原子刷新 latest；凭据随包保存，权限由 umask 保证为私有。
CONFIG_TMP="$BACKUP_DIR/.config-latest.tar.gz.tmp"
tar -C /opt/tplanner-sync -czf "$CONFIG_TMP" \
    sync-server/deploy/nats-server.conf \
    sync-server/deploy/nats.creds
mv -f "$CONFIG_TMP" "$BACKUP_DIR/config-latest.tar.gz"

echo "verified backup written to $BACKUP_DIR"
