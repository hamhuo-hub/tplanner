import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { openDatabase } from '../src/state/database.js';
import { buildSnapshot } from '../src/materializer/snapshot.js';
import { emptyState } from '../src/materializer/reducer.js';
import { canonicalizeTaskPayload } from '../src/state/canonicalTask.js';
import { migrateCanonicalTaskEntities } from '../src/state/canonicalTaskMigration.js';

const LEGACY_PAYLOAD = {
  title: '旧任务',
  note: '',
  completed: false,
  type: 'task',
  start: '2026-09-01T01:02:03.000Z',
  end: '2026-09-01T02:02:03.000Z',
  timezone: 'Asia/Shanghai',
  checklist: [
    { id: 'a', text: '保留这段文字', completed: false, futureItemField: 7 },
    { id: 'b', title: '已有标题优先', text: '旧副本', completed: true },
  ],
  recurrenceType: 'weekly',
  recurrenceCount: 10,
  alarmEnabled: true,
  alarmOffsetMinutes: 30,
  colorId: 3,
  lat: 31.23,
  lng: 121.47,
  extras: {
    recurrenceType: 'weekly',
    recurrenceCount: 10,
    unknownNested: { must: 'survive' },
  },
  futureRootField: { also: 'survives' },
};

function seedLegacyDatabase(db) {
  db.prepare(`
    INSERT INTO entities
      (entity_type, entity_id, lifecycle, payload_json, last_broker_sequence,
       created_at, updated_at, deleted_at)
    VALUES ('task', 'task-1', 'active', ?, 42, 1, 1, NULL)
  `).run(JSON.stringify(LEGACY_PAYLOAD));

  const state = emptyState();
  state.tasks['task-1'] = { ...LEGACY_PAYLOAD, lifecycle: 'active', deletedAt: null };
  const snapshot = buildSnapshot({
    state,
    snapshotVersion: 1,
    parentVersion: 0,
    serverInstanceId: 'srv-migration-test',
    brokerFromSequence: 42,
    brokerToSequence: 42,
    createdAt: '2026-08-20T00:00:00.000Z',
  });
  db.prepare(`
    INSERT INTO snapshots
      (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
       state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
       created_at)
    VALUES (1, 0, 42, 42, 3, ?, ?, ?, ?, ?, 1)
  `).run(
    snapshot.stateHash,
    snapshot.compressedHash,
    snapshot.compressed,
    snapshot.manifest.uncompressedBytes,
    snapshot.manifest.compressedBytes,
  );
  db.prepare('INSERT INTO latest_snapshot (singleton_id, version, state_hash) VALUES (1, 1, ?)')
    .run(snapshot.stateHash);
}

test('canonical task normalizer preserves extras and unknown fields while lifting legacy fields', () => {
  const canonical = canonicalizeTaskPayload(LEGACY_PAYLOAD);

  assert.deepEqual(canonical.checklist, [
    { id: 'a', completed: false, futureItemField: 7, title: '保留这段文字' },
    { id: 'b', title: '已有标题优先', completed: true },
  ]);
  assert.deepEqual(canonical.recurrence, { frequency: 'weekly', count: 10 });
  assert.deepEqual(canonical.schedule, {
    startAt: LEGACY_PAYLOAD.start,
    endAt: LEGACY_PAYLOAD.end,
  });
  assert.equal(canonical.itemType, 'task');
  assert.deepEqual(canonical.alarm, { enabled: true, offsetMinutes: 30 });
  assert.equal(canonical.colorId, 3);
  assert.deepEqual(canonical.location, { lat: 31.23, lng: 121.47 });
  assert.equal(canonical.listId, null);
  assert.deepEqual(canonical.extras, {
    ...LEGACY_PAYLOAD.extras,
    timezone: 'Asia/Shanghai',
    futureRootField: LEGACY_PAYLOAD.futureRootField,
  }, 'extras must preserve existing and unknown task fields');
  assert.equal('futureRootField' in canonical, false);
  for (const oldKey of [
    'type', 'start', 'end', 'recurrenceType', 'recurrenceCount',
    'alarmEnabled', 'alarmOffsetMinutes', 'lat', 'lng',
  ]) {
    assert.equal(oldKey in canonical, false, `${oldKey} should be lifted into the canonical model`);
  }
});

test('canonical task normalizer fills every missing field without overwriting existing nested data', () => {
  const canonical = canonicalizeTaskPayload({
    title: '最小历史任务',
    schedule: { startAt: '2026-09-01T00:00:00.000Z', timezone: 'Asia/Shanghai' },
    alarm: { enabled: true, futureAlarmField: 'keep' },
    location: { lat: 31.23, futureLocationField: 'keep' },
    futureRootField: 7,
  });

  assert.deepEqual(canonical, {
    title: '最小历史任务',
    note: '',
    completed: false,
    itemType: 'task',
    schedule: {
      startAt: '2026-09-01T00:00:00.000Z',
      endAt: null,
      timezone: 'Asia/Shanghai',
    },
    recurrence: null,
    alarm: { enabled: true, offsetMinutes: 0, futureAlarmField: 'keep' },
    colorId: 0,
    location: { lat: 31.23, lng: null, futureLocationField: 'keep' },
    extras: { futureRootField: 7 },
    listId: null,
    checklist: [],
  });
});

test('database migration atomically publishes a canonical snapshot and is idempotent', () => {
  const db = openDatabase(':memory:');
  seedLegacyDatabase(db);

  const first = migrateCanonicalTaskEntities(db, {
    serverInstanceId: 'srv-migration-test',
    now: () => 1_800_000_000_000,
  });
  assert.equal(first.changedTasks, 1);
  assert.equal(first.snapshotVersion, 2);

  const stored = JSON.parse(db.prepare("SELECT payload_json FROM entities WHERE entity_id = 'task-1'").get().payload_json);
  for (const field of [
    'title', 'note', 'completed', 'itemType', 'schedule', 'recurrence', 'alarm',
    'colorId', 'location', 'extras', 'listId', 'checklist',
  ]) {
    assert.equal(Object.hasOwn(stored, field), true, `migration must materialize ${field}`);
  }
  assert.equal(stored.checklist[0].title, '保留这段文字');
  assert.equal('text' in stored.checklist[0], false);
  assert.deepEqual(stored.extras, {
    ...LEGACY_PAYLOAD.extras,
    timezone: 'Asia/Shanghai',
    futureRootField: LEGACY_PAYLOAD.futureRootField,
  });
  assert.equal('futureRootField' in stored, false);
  assert.deepEqual(stored.schedule, {
    startAt: LEGACY_PAYLOAD.start,
    endAt: LEGACY_PAYLOAD.end,
  });
  assert.equal(stored.itemType, 'task');

  const latest = db.prepare('SELECT version, state_hash FROM latest_snapshot').get();
  assert.equal(latest.version, 2);
  assert.equal(latest.state_hash, first.stateHash);
  const row = db.prepare('SELECT compressed_payload FROM snapshots WHERE version = 2').get();
  const envelope = JSON.parse(gunzipSync(row.compressed_payload));
  assert.equal(envelope.state.tasks['task-1'].checklist[0].title, '保留这段文字');
  assert.deepEqual(envelope.state.tasks['task-1'].extras, {
    ...LEGACY_PAYLOAD.extras,
    timezone: 'Asia/Shanghai',
    futureRootField: LEGACY_PAYLOAD.futureRootField,
  });
  assert.equal(
    db.prepare("SELECT COUNT(*) AS c FROM publication_outbox WHERE dedupe_key = 'snapshot.ready:v2'").get().c,
    1,
  );

  const second = migrateCanonicalTaskEntities(db, {
    serverInstanceId: 'srv-migration-test',
    now: () => 1_800_000_001_000,
  });
  assert.deepEqual(second, { changedTasks: 0, snapshotVersion: null, stateHash: null });
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 2);
  db.close();
});
