#!/usr/bin/env bash
# External watchdog for the three-process V3 stack. systemd invokes this every
# minute; a failed end-to-end readiness check triggers one bounded restart.
set -euo pipefail

API=http://127.0.0.1:37401

if curl --fail --silent --show-error --max-time 10 "$API/health/ready" >/dev/null; then
    exit 0
fi

logger -t tplanner-sync-health "readiness failed; restarting NATS, State Builder and API"
systemctl restart nats-server.service tplanner-state-builder.service tplanner-sync-api.service

# NATS may need several seconds to restore JetStream on a Raspberry Pi.
for _attempt in $(seq 1 12); do
    if curl --fail --silent --show-error --max-time 5 "$API/health/ready" >/dev/null 2>&1; then
        exit 0
    fi
    sleep 5
done

logger -t tplanner-sync-health "readiness still failing after recovery restart"
exit 1
