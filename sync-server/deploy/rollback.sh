#!/usr/bin/env bash
# 恢复 V1 写入(撤销 cutover.sh)。
# 用法: sudo bash sync-server/deploy/rollback.sh
set -euo pipefail

UNIT=tplanner-sync-api.service
OVERRIDE_FILE=/etc/systemd/system/${UNIT}.d/10-v1-writes-disabled.conf

if [ -f "$OVERRIDE_FILE" ]; then
    rm -f "$OVERRIDE_FILE"
fi
systemctl daemon-reload
systemctl restart "$UNIT"
echo "已恢复 V1 写入。"
