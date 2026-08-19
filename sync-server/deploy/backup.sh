#!/usr/bin/env bash
# tPlanner Sync V3 备份脚本(骨架)
# SQLite online backup 与 JetStream consumer state 导出由 node 工具完成(commit 24),
# 本脚本先负责 JetStream 文件存储与配置的冷备份,供 daily cron 调用。
# 建议: 每日异机备份用 restic/rclone 推走 $DATA_DIR/backups。
set -euo pipefail

DATA_DIR=/var/lib/tplanner-sync
BACKUP_DIR="$DATA_DIR/backups"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

install -d "$BACKUP_DIR"

# JetStream 文件存储(单节点、无写入时安全;有写入时以 commit 24 的在线备份为准)
if [ -d "$DATA_DIR/jetstream" ]; then
    tar -C "$DATA_DIR" -czf "$BACKUP_DIR/jetstream-$STAMP.tar.gz" jetstream
fi

# 配置与凭据
tar -C /opt/tplanner-sync -czf "$BACKUP_DIR/config-$STAMP.tar.gz" \
    sync-server/deploy/nats-server.conf sync-server/deploy/nats.creds

# 保留策略:近 24 小时每小时一个、近 30 天每天一个
find "$BACKUP_DIR" -name 'jetstream-*.tar.gz' -mmin +1440 -mtime -30 -delete 2>/dev/null || true

echo "backup written to $BACKUP_DIR ($STAMP)"
