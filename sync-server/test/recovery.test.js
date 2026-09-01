import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { openDatabase } from '../src/state/database.js';
import { createLease } from '../src/materializer/lease.js';
import { ensureBootstrapSnapshot } from '../src/materializer/materializer.js';
import {
  assessJetStreamReplayWindow,
  publishRecoverySnapshot,
} from '../src/state/recovery.js';

test('recovery snapshot atomically advances above operator high-water with unchanged state', () => {
  const db = openDatabase(':memory:');
  ensureBootstrapSnapshot(db, {
    serverInstanceId: 'srv-recovery-test',
    now: () => 100,
  });
  const original = db.prepare('SELECT version, state_hash FROM latest_snapshot').get();

  const result = publishRecoverySnapshot(db, {
    preRestoreHighWater: 10,
    serverInstanceId: 'srv-recovery-test',
    now: () => 200,
    publicationId: () => 'recovery-publication',
  });

  assert.equal(result.created, true);
  assert.equal(result.snapshotVersion, 11);
  assert.equal(result.parentVersion, 1);
  assert.equal(result.stateHash, original.state_hash);
  assert.equal(result.brokerToSequence, 0);
  assert.deepEqual(
    db.prepare('SELECT version, state_hash FROM latest_snapshot').get(),
    { version: 11, state_hash: original.state_hash },
  );
  const snapshot = db.prepare(`
    SELECT parent_version, broker_from_sequence, broker_to_sequence, state_hash,
           compressed_payload
      FROM snapshots
     WHERE version = 11
  `).get();
  assert.deepEqual(
    {
      parentVersion: snapshot.parent_version,
      brokerFromSequence: snapshot.broker_from_sequence,
      brokerToSequence: snapshot.broker_to_sequence,
      stateHash: snapshot.state_hash,
    },
    {
      parentVersion: 1,
      brokerFromSequence: 0,
      brokerToSequence: 0,
      stateHash: original.state_hash,
    },
  );
  const envelope = JSON.parse(gunzipSync(snapshot.compressed_payload));
  assert.equal(envelope.snapshotVersion, 11);
  assert.equal(envelope.parentVersion, 1);
  assert.equal(envelope.serverInstanceId, 'srv-recovery-test');
  assert.equal(envelope.brokerToSequence, 0);
  assert.deepEqual(envelope.state, {
    tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {},
  });
  const outbox = db.prepare(`
    SELECT publication_type, dedupe_key, payload_json, state
      FROM publication_outbox
     WHERE publication_id = 'recovery-publication'
  `).get();
  assert.equal(outbox.publication_type, 'snapshot.ready');
  assert.equal(outbox.dedupe_key, 'snapshot.ready:v11');
  assert.equal(outbox.state, 'pending');
  assert.equal(JSON.parse(outbox.payload_json).snapshotVersion, 11);

  const repeated = publishRecoverySnapshot(db, {
    preRestoreHighWater: 10,
    serverInstanceId: 'srv-recovery-test',
    now: () => 300,
  });
  assert.equal(repeated.created, false);
  assert.equal(repeated.snapshotVersion, 11);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 2);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM publication_outbox').get().c, 2);
  db.close();
});

test('recovery snapshot refuses an active writer lease without partial writes', () => {
  const db = openDatabase(':memory:');
  ensureBootstrapSnapshot(db, { serverInstanceId: 'srv-recovery-test', now: () => 100 });
  createLease(db).acquire('live-builder', 1_000);

  assert.throws(
    () => publishRecoverySnapshot(db, {
      preRestoreHighWater: 10,
      serverInstanceId: 'srv-recovery-test',
      now: () => 1_001,
    }),
    /lease is still active.*live-builder/,
  );
  assert.equal(db.prepare('SELECT MAX(version) AS version FROM snapshots').get().version, 1);
  assert.equal(db.prepare('SELECT version FROM latest_snapshot').get().version, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM publication_outbox').get().c, 1);
  db.close();
});

test('recovery snapshot rolls back when snapshot construction fails', () => {
  const db = openDatabase(':memory:');
  ensureBootstrapSnapshot(db, { serverInstanceId: 'srv-recovery-test', now: () => 100 });

  assert.throws(
    () => publishRecoverySnapshot(db, {
      preRestoreHighWater: 20,
      serverInstanceId: 'srv-recovery-test',
      now: () => 200,
      buildSnapshotFn: () => { throw new Error('injected snapshot failure'); },
    }),
    /injected snapshot failure/,
  );
  assert.equal(db.prepare('SELECT MAX(version) AS version FROM snapshots').get().version, 1);
  assert.equal(db.prepare('SELECT version FROM latest_snapshot').get().version, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM publication_outbox').get().c, 1);
  db.close();
});

test('JetStream replay window requires both a non-reused tail and every post-backup message', () => {
  assert.deepEqual(
    assessJetStreamReplayWindow({
      firstSequence: 80,
      lastSequence: 150,
      materializedThroughSequence: 100,
    }),
    { safe: true, reason: null, requiredNextSequence: 101 },
  );
  assert.deepEqual(
    assessJetStreamReplayWindow({
      firstSequence: 101,
      lastSequence: 100,
      materializedThroughSequence: 100,
    }),
    { safe: true, reason: null, requiredNextSequence: 101 },
    'an empty retained tail immediately after the DB watermark is safe',
  );
  assert.deepEqual(
    assessJetStreamReplayWindow({
      firstSequence: 102,
      lastSequence: 150,
      materializedThroughSequence: 100,
    }),
    { safe: false, reason: 'REPLAY_GAP', requiredNextSequence: 101 },
  );
  assert.deepEqual(
    assessJetStreamReplayWindow({
      firstSequence: 1,
      lastSequence: 99,
      materializedThroughSequence: 100,
    }),
    { safe: false, reason: 'STREAM_BEHIND_DATABASE', requiredNextSequence: 101 },
  );
});
