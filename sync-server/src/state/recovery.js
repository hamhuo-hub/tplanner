// Offline disaster-recovery primitives. These functions do not stop services:
// callers must do that first, and the live-lease check below is the final
// single-writer fence. Recovery never deletes or edits the lease row.
import { randomUUID } from 'node:crypto';
import { loadStateFromDb } from '../materializer/materializer.js';
import { buildSnapshot } from '../materializer/snapshot.js';

function requireSafeNonNegativeInteger(value, name, { allowMax = true } = {}) {
  if (!Number.isSafeInteger(value) || value < 0 || (!allowMax && value >= Number.MAX_SAFE_INTEGER)) {
    throw new Error(`${name} must be a non-negative safe integer`);
  }
}

/**
 * Prove that every stream message newer than the restored DB is still
 * retained, while also rejecting a reset/behind stream whose sequence would
 * collide with broker sequences already recorded in SQLite.
 */
export function assessJetStreamReplayWindow({
  firstSequence,
  lastSequence,
  materializedThroughSequence,
}) {
  for (const [name, value] of Object.entries({
    firstSequence,
    lastSequence,
    materializedThroughSequence,
  })) {
    requireSafeNonNegativeInteger(value, name);
  }
  if (firstSequence > lastSequence + 1) {
    return { safe: false, reason: 'INVALID_STREAM_RANGE' };
  }
  if (lastSequence < materializedThroughSequence) {
    return {
      safe: false,
      reason: 'STREAM_BEHIND_DATABASE',
      requiredNextSequence: materializedThroughSequence + 1,
    };
  }

  const requiredNextSequence = materializedThroughSequence + 1;
  if (firstSequence > requiredNextSequence) {
    return {
      safe: false,
      reason: 'REPLAY_GAP',
      requiredNextSequence,
    };
  }
  return { safe: true, reason: null, requiredNextSequence };
}

/**
 * Publish an immutable checkpoint after restoring an older SQLite image.
 * The checkpoint's content hash and broker watermark are unchanged, but its
 * version is greater than the high-water observed before restore. Snapshot,
 * latest pointer and publication outbox are one SQLite transaction.
 *
 * A repeat invocation is an idempotent no-op once latest already exceeds the
 * supplied high-water.
 */
export function publishRecoverySnapshot(
  db,
  {
    preRestoreHighWater,
    serverInstanceId,
    now = () => Date.now(),
    buildSnapshotFn = buildSnapshot,
    publicationId = randomUUID,
  },
) {
  requireSafeNonNegativeInteger(preRestoreHighWater, 'preRestoreHighWater', { allowMax: false });
  if (typeof serverInstanceId !== 'string' || serverInstanceId === '') {
    throw new Error('serverInstanceId is required');
  }

  const publish = db.transaction(() => {
    const createdAt = now();
    if (!Number.isSafeInteger(createdAt) || createdAt < 0) {
      throw new Error('now() must return a non-negative safe integer');
    }
    const liveLease = db.prepare(`
      SELECT owner_id, lease_expires_at
        FROM state_builder_lease
       WHERE singleton_id = 1 AND lease_expires_at > ?
    `).get(createdAt);
    if (liveLease) {
      throw new Error(
        `state builder lease is still active (${liveLease.owner_id}) until ${liveLease.lease_expires_at}`,
      );
    }

    const latest = db.prepare(`
      SELECT s.version, s.broker_to_sequence, s.state_hash
        FROM latest_snapshot l
        JOIN snapshots s ON s.version = l.version
       WHERE l.singleton_id = 1
    `).get();
    if (!latest) throw new Error('restored database has no latest immutable snapshot');

    const maxVersion = Number(
      db.prepare('SELECT COALESCE(MAX(version), 0) AS version FROM snapshots').get().version,
    );
    if (Number(latest.version) !== maxVersion) {
      throw new Error(
        `latest snapshot pointer ${latest.version} does not match maximum snapshot ${maxVersion}`,
      );
    }

    const state = loadStateFromDb(db);
    const targetVersion = Number(latest.version) > preRestoreHighWater
      ? Number(latest.version)
      : preRestoreHighWater + 1;
    const candidate = buildSnapshotFn({
      state,
      snapshotVersion: targetVersion,
      parentVersion: Number(latest.version),
      serverInstanceId,
      brokerFromSequence: Number(latest.broker_to_sequence),
      brokerToSequence: Number(latest.broker_to_sequence),
      createdAt: new Date(createdAt).toISOString(),
    });
    if (candidate.stateHash !== latest.state_hash) {
      throw new Error(
        `restored entities hash ${candidate.stateHash} does not match latest snapshot ${latest.state_hash}`,
      );
    }

    if (Number(latest.version) > preRestoreHighWater) {
      return {
        created: false,
        snapshotVersion: Number(latest.version),
        parentVersion: Number(latest.version),
        stateHash: latest.state_hash,
        brokerToSequence: Number(latest.broker_to_sequence),
        manifest: null,
      };
    }

    db.prepare(`
      INSERT INTO snapshots
        (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
         state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
         created_at)
      VALUES (?, ?, ?, ?, 3, ?, ?, ?, ?, ?, ?)
    `).run(
      candidate.manifest.snapshotVersion,
      candidate.manifest.parentVersion,
      latest.broker_to_sequence,
      latest.broker_to_sequence,
      candidate.stateHash,
      candidate.compressedHash,
      candidate.compressed,
      candidate.manifest.uncompressedBytes,
      candidate.manifest.compressedBytes,
      createdAt,
    );
    db.prepare(`
      UPDATE latest_snapshot
         SET version = ?, state_hash = ?
       WHERE singleton_id = 1
    `).run(candidate.manifest.snapshotVersion, candidate.stateHash);
    db.prepare(`
      INSERT INTO publication_outbox
        (publication_id, publication_type, dedupe_key, payload_json, state,
         attempt_count, next_attempt_at, created_at)
      VALUES (?, 'snapshot.ready', ?, ?, 'pending', 0, 0, ?)
    `).run(
      publicationId(),
      `snapshot.ready:v${candidate.manifest.snapshotVersion}`,
      JSON.stringify(candidate.manifest),
      createdAt,
    );

    return {
      created: true,
      snapshotVersion: candidate.manifest.snapshotVersion,
      parentVersion: candidate.manifest.parentVersion,
      stateHash: candidate.stateHash,
      brokerToSequence: Number(latest.broker_to_sequence),
      manifest: candidate.manifest,
    };
  });

  return publish();
}
