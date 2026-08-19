// API 只读仓库:能力、回执、快照、设备进度(见 docs/sync-v3.md §11)。
// 直读 SQLite —— 快照/回执只有 State Builder 一个写者,读路径不需要缓存。
import { acceptedThrough, receiptsForDeviceAfter } from '../state/receipts.js';

const MAX_BATCH_COMMANDS = 100;
const MAX_BATCH_BYTES = 256 * 1024;

export function createStore(db, { serverInstanceId }) {
  const latestRow = () =>
    db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get();

  const snapshotRowByVersion = (version) =>
    db.prepare('SELECT * FROM snapshots WHERE version = ?').get(version);

  const toSnapshotDto = (row) =>
    row && {
      version: row.version,
      parentVersion: row.parent_version,
      schemaVersion: row.schema_version,
      stateHash: row.state_hash,
      compressedHash: row.compressed_hash,
      compressedPayload: row.compressed_payload, // Buffer
      uncompressedBytes: row.uncompressed_bytes,
      compressedBytes: row.compressed_bytes,
      createdAt: row.created_at,
    };

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
        protocolVersion: 3,
        schemaVersion: 3,
        serverInstanceId,
        latestSnapshotVersion: latest?.version ?? 0,
        maxBatchCommands: MAX_BATCH_COMMANDS,
        maxBatchBytes: MAX_BATCH_BYTES,
      };
    },
    receiptsForDevice(deviceId, afterClientSequence) {
      return receiptsForDeviceAfter(db, deviceId, afterClientSequence);
    },
    acceptedThrough(deviceId) {
      return acceptedThrough(db, deviceId);
    },
    latestSnapshot() {
      const latest = latestRow();
      return latest ? toSnapshotDto(snapshotRowByVersion(latest.version)) : null;
    },
    snapshotByVersion(version) {
      return toSnapshotDto(snapshotRowByVersion(version));
    },
    recordSnapshotAck(deviceId, { version, stateHash }, lastSeenAt = Date.now()) {
      upsertAck.run({ deviceId, version, stateHash, lastSeenAt });
    },
  };
}
