// API 只读仓库:能力、回执、快照、设备进度(见 docs/sync-v3.md §11/§14)。
//
// 快照不可变(版本内容寻址),因此压缩载荷可以按版本安全缓存且永不过期;
// "latest" 指针每次直读 SQLite(单行),无需跨进程失效。缓存上限 5 个
// (§14:最近 3–5 个),重启从 SQLite 恢复。
import { acceptedThrough, receiptsForDeviceAfter } from '../state/receipts.js';
import { SOFTWARE_VERSION } from '../version.js';

const MAX_BATCH_COMMANDS = 100;
const MAX_BATCH_BYTES = 256 * 1024;
const PAYLOAD_CACHE_MAX = 5;
const NOTIFICATION_POLL_MS = 50;

export function createStore(db, { serverInstanceId }) {
  const latestRow = () =>
    db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get();

  const metaByVersion = db.prepare(`
    SELECT version, parent_version, schema_version, state_hash, compressed_hash,
           uncompressed_bytes, compressed_bytes, broker_to_sequence, created_at
    FROM snapshots WHERE version = ?
  `);

  const payloadByVersion = db.prepare('SELECT compressed_payload FROM snapshots WHERE version = ?');

  // 版本 → Buffer(LRU:命中移到末尾,超限淘汰最旧)
  const payloadCache = new Map();

  const toMetaDto = (row) =>
    row && {
      version: row.version,
      parentVersion: row.parent_version,
      schemaVersion: row.schema_version,
      stateHash: row.state_hash,
      compressedHash: row.compressed_hash,
      uncompressedBytes: row.uncompressed_bytes,
      compressedBytes: row.compressed_bytes,
      brokerToSequence: row.broker_to_sequence,
      createdAt: row.created_at,
      serverInstanceId,
    };

  function snapshotMeta(version) {
    return toMetaDto(metaByVersion.get(version));
  }

  function snapshotPayload(version) {
    const hit = payloadCache.get(version);
    if (hit) {
      payloadCache.delete(version);
      payloadCache.set(version, hit);
      return hit;
    }
    const row = payloadByVersion.get(version);
    if (!row) return null;
    const payload = Buffer.from(row.compressed_payload);
    payloadCache.set(version, payload);
    if (payloadCache.size > PAYLOAD_CACHE_MAX) {
      payloadCache.delete(payloadCache.keys().next().value);
    }
    return payload;
  }

  function latestSnapshotMeta() {
    const latest = latestRow();
    return latest ? snapshotMeta(latest.version) : null;
  }

  async function waitForLatestSnapshot(afterVersion, { waitMs, pollMs = NOTIFICATION_POLL_MS }) {
    const deadline = Date.now() + waitMs;
    while (true) {
      const meta = latestSnapshotMeta();
      if ((meta?.version ?? 0) > afterVersion) return meta;

      const remaining = deadline - Date.now();
      if (remaining <= 0) return meta;
      await new Promise((resolve) => setTimeout(resolve, Math.min(pollMs, remaining)));
    }
  }

  const upsertAck = db.prepare(`
    INSERT INTO device_progress
      (device_id, accepted_client_sequence, installed_snapshot_version,
       installed_snapshot_hash, last_seen_at, protocol_version)
    VALUES (@deviceId, 0, @version, @stateHash, @lastSeenAt, 3)
    ON CONFLICT(device_id) DO UPDATE SET
      installed_snapshot_version = excluded.installed_snapshot_version,
      installed_snapshot_hash = excluded.installed_snapshot_hash,
      last_seen_at = excluded.last_seen_at
  `);

  return {
    capabilities() {
      const latest = latestRow();
      return {
        softwareVersion: SOFTWARE_VERSION,
        protocolVersion: 3,
        schemaVersion: 3,
        serverInstanceId,
        latestSnapshotVersion: latest?.version ?? 0,
        maxBatchCommands: MAX_BATCH_COMMANDS,
        maxBatchBytes: MAX_BATCH_BYTES,
      };
    },
    receiptsForDevice(deviceId, afterClientSequence) {
      // The public receipt schema deliberately excludes internal audit columns. Optional values
      // are omitted rather than serialized as null so strict JSON Schema clients can consume GAP
      // receipts (which have no immutable snapshot coverage yet).
      return receiptsForDeviceAfter(db, deviceId, afterClientSequence).map((receipt) => ({
        commandId: receipt.commandId,
        clientSequence: receipt.clientSequence,
        brokerSequence: receipt.brokerSequence,
        status: receipt.status,
        ...(receipt.errorCode == null ? {} : { errorCode: receipt.errorCode }),
        ...(receipt.snapshotVersion == null ? {} : { snapshotVersion: receipt.snapshotVersion }),
      }));
    },
    acceptedThrough(deviceId) {
      return acceptedThrough(db, deviceId);
    },
    latestSnapshotMeta,
    waitForLatestSnapshot,
    snapshotMeta,
    snapshotPayload,
    recordSnapshotAck(deviceId, { version, stateHash }, lastSeenAt = Date.now()) {
      upsertAck.run({ deviceId, version, stateHash, lastSeenAt });
    },
  };
}
