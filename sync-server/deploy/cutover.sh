#!/usr/bin/env bash
# V1 → V3 切换终点(见 docs/sync-v3.md §17/§21):禁用旧客户端写入。
# 前提:所有客户端已升级 V3,且观察期满足(连续 7 天无 V1 PUT)。
# 用法: sudo bash sync-server/deploy/cutover.sh
set -euo pipefail

UNIT=tplanner-sync-api.service
OVERRIDE_DIR=/etc/systemd/system/${UNIT}.d
OVERRIDE_FILE=${OVERRIDE_DIR}/10-v1-writes-disabled.conf
API=http://127.0.0.1:37401

echo "== 预检 =="
systemctl is-active "$UNIT" >/dev/null || { echo "API 未运行,请先启动"; exit 1; }
curl -fsS "$API/health/ready" >/dev/null || { echo "/health/ready 不健康,先修复再切换"; exit 1; }

echo "== 写入禁用 =="
install -d "$OVERRIDE_DIR"
printf '[Service]\nEnvironment=TPLANNER_DISABLE_V1_WRITES=1\n' > "$OVERRIDE_FILE"
systemctl daemon-reload
systemctl restart "$UNIT"

echo "== 验证 =="
sleep 2
curl -fsS "$API/health/ready" >/dev/null || { echo "重启后不健康,立即回滚"; bash "$(dirname "$0")/rollback.sh"; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' -d '[]' "$API/tplanner/events")
if [ "$code" != "410" ]; then
    echo "期望 V1 PUT 返回 410,实际 $code,立即回滚"
    bash "$(dirname "$0")/rollback.sh"
    exit 1
fi

echo "完成:V1 写入已禁用(读取仍可用)。观察期 7 天后删除 V1 路由(§21)。"
echo "回滚: sudo bash sync-server/deploy/rollback.sh"
