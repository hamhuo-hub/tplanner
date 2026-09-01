// One-shot, idempotent migration from pre-canonical task payloads to the
// canonical V3 task model. Run with API + State Builder stopped so this remains
// the only writer. Unknown task fields and `extras` are preserved verbatim.
import { randomUUID } from 'node:crypto';
import { canonicalizeTaskPayload } from './canonicalTask.js';
import { loadStateFromDb } from '../materializer/materializer.js';
import { buildSnapshot } from '../materializer/snapshot.js';
import { computeJournalChanges, insertJournalCommit } from './journal.js';

export function migrateCanonicalTaskEntities(
  db,
  {
    serverInstanceId,
    now = () => Date.now(),
  },
) {
  if (typeof serverInstanceId !== 'string' || serverInstanceId === '') {
    throw new Error('serverInstanceId is required');
  }

  const updates = db
    .prepare("SELECT entity_id, payload_json FROM entities WHERE entity_type = 'task' ORDER BY entity_id")
    .all()
    .map((row) => {
      const payload = JSON.parse(row.payload_json);
      const canonical = canonicalizeTaskPayload(payload);
      return { entityId: row.entity_id, before: row.payload_json, after: JSON.stringify(canonical) };
    })
    .filter((row) => row.before !== row.after);

  if (updates.length === 0) {
    return { changedTasks: 0, snapshotVersion: null, stateHash: null };
  }

  const updatePayload = db.prepare(`
    UPDATE entities
       SET payload_json = @payloadJson
     WHERE entity_type = 'task' AND entity_id = @entityId
  `);
  // 迁移前的 canonical state(事务外读取,作为 journal diff 的 fromState)。
  const fromState = loadStateFromDb(db);
  const insertSnapshot = db.prepare(`
    INSERT INTO snapshots
      (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
       state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
       created_at)
    VALUES
      (@version, @parentVersion, @brokerSequence, @brokerSequence, 3,
       @stateHash, @compressedHash, @compressedPayload, @uncompressedBytes, @compressedBytes,
       @createdAt)
  `);
  const upsertLatest = db.prepare(`
    INSERT INTO latest_snapshot (singleton_id, version, state_hash)
    VALUES (1, @version, @stateHash)
    ON CONFLICT(singleton_id) DO UPDATE SET
      version = excluded.version,
      state_hash = excluded.state_hash
  `);
  const insertOutbox = db.prepare(`
    INSERT INTO publication_outbox
      (publication_id, publication_type, dedupe_key, payload_json, state,
       attempt_count, next_attempt_at, created_at)
    VALUES
      (@publicationId, 'snapshot.ready', @dedupeKey, @payloadJson, 'pending', 0, 0, @createdAt)
  `);

  const migrate = db.transaction(() => {
    for (const row of updates) {
      updatePayload.run({ entityId: row.entityId, payloadJson: row.after });
    }

    const previous = db.prepare(`
      SELECT version, broker_to_sequence, state_hash
        FROM snapshots
       ORDER BY version DESC
       LIMIT 1
    `).get();
    const parentVersion = previous?.version ?? 0;
    const version = parentVersion + 1;
    const brokerSequence = previous?.broker_to_sequence ?? 0;
    const createdAt = now();
    const snapshot = buildSnapshot({
      state: loadStateFromDb(db),
      snapshotVersion: version,
      parentVersion,
      serverInstanceId,
      brokerFromSequence: brokerSequence,
      brokerToSequence: brokerSequence,
      createdAt: new Date(createdAt).toISOString(),
    });

    if (previous?.state_hash === snapshot.stateHash) {
      return { snapshot: null };
    }

    insertSnapshot.run({
      version,
      parentVersion,
      brokerSequence,
      stateHash: snapshot.stateHash,
      compressedHash: snapshot.compressedHash,
      compressedPayload: snapshot.compressed,
      uncompressedBytes: snapshot.manifest.uncompressedBytes,
      compressedBytes: snapshot.manifest.compressedBytes,
      createdAt,
    });
    // 一次性迁移也是 snapshot 生产者:同一事务写 journal commit,
    // 携带真实 task.put diff(从 canonical 前后的 state 推导)。
    insertJournalCommit(db, {
      snapshotVersion: version,
      parentVersion,
      brokerFromSequence: brokerSequence,
      brokerToSequence: brokerSequence,
      stateHashAfter: snapshot.stateHash,
      changes: computeJournalChanges({
        fromState,
        toState: loadStateFromDb(db),
        brokerToSequence: brokerSequence,
      }),
      createdAt,
    });
    upsertLatest.run({ version, stateHash: snapshot.stateHash });
    insertOutbox.run({
      publicationId: randomUUID(),
      dedupeKey: `snapshot.ready:v${version}`,
      payloadJson: JSON.stringify(snapshot.manifest),
      createdAt,
    });
    return { snapshot };
  });

  const { snapshot } = migrate();
  return {
    changedTasks: updates.length,
    snapshotVersion: snapshot?.manifest.snapshotVersion ?? null,
    stateHash: snapshot?.stateHash ?? null,
  };
}
