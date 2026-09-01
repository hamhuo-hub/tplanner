import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { openDatabase, migrate } from '../src/state/database.js';
import {
  createMaterializer,
  ensureBootstrapSnapshot,
  ensureReceiptCoverageSnapshot,
} from '../src/materializer/materializer.js';
import { applyCommand, emptyState } from '../src/materializer/reducer.js';
import { buildSnapshot, canonicalStateHash } from '../src/materializer/snapshot.js';
import {
  applyJournalCommit,
  computeJournalChanges,
  getJournalMeta,
  loadJournalCommit,
} from '../src/state/journal.js';
import { insertReceipt } from '../src/state/receipts.js';
import { publishRecoverySnapshot } from '../src/state/recovery.js';
import { migrateCanonicalTaskEntities } from '../src/state/canonicalTaskMigration.js';

const SERVER_ID = 'srv-journal-test';

const fixturePath = (name) =>
  fileURLToPath(new URL(`../../sync-v3/protocol/v3/fixtures/reducer/${name}`, import.meta.url));

async function loadFixtureInput() {
  return JSON.parse(await readFile(fixturePath('sequence-01.input.json'), 'utf8')).commands;
}

// fixture 是扁平的 broker 命令流(无 client batch 信封);补上 batchId 满足回执表 NOT NULL。
const withBatchId = (entries) => entries.map((e, i) => ({ ...e, batchId: `batch-${i + 1}` }));

function entry(brokerSequence, deviceId, command, batchId = 'b1') {
  return { brokerSequence, deviceId, batchId, command };
}

function cmd(type, aggregateId, args, commandId, clientSequence) {
  return { commandId, clientSequence, type, aggregateId, arguments: args ?? {} };
}

function snapshotState(db, version) {
  const row = db.prepare('SELECT compressed_payload FROM snapshots WHERE version = ?').get(version);
  assert.ok(row, `snapshot ${version} must exist`);
  return JSON.parse(gunzipSync(row.compressed_payload).toString('utf8')).state;
}

function allCommits(db) {
  return db
    .prepare('SELECT snapshot_version FROM change_commits ORDER BY snapshot_version')
    .all()
    .map((r) => r.snapshot_version);
}

test('one applied command produces one commit with one full-entity task.put', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const result = m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: '任务一' }, 'c1', 1)),
  ]);
  const snapshot = result.snapshot;
  const commit = loadJournalCommit(db, snapshot.manifest.snapshotVersion);

  assert.equal(commit.snapshotVersion, 1);
  assert.equal(commit.parentVersion, 0);
  assert.equal(commit.brokerFromSequence, 1);
  assert.equal(commit.brokerToSequence, 1);
  assert.equal(commit.stateHashAfter, snapshot.manifest.stateHash);
  assert.equal(commit.changeCount, 1);
  assert.equal(commit.payloadBytes, Buffer.byteLength(JSON.stringify(m.getState().tasks.t1), 'utf8'));

  const change = commit.changes[0];
  assert.equal(change.type, 'task.put');
  assert.equal(change.entityType, 'task');
  assert.equal(change.entityId, 't1');
  assert.equal(change.entityBrokerSequence, 1);
  // 完整 canonical entity:含 lifecycle/deletedAt,客户端整实体替换
  assert.deepEqual(change.value, m.getState().tasks.t1);
  assert.equal(change.value.lifecycle, 'active');
  assert.equal(change.value.deletedAt, null);
  assert.equal(change.value.title, '任务一');
  assert.ok(Object.hasOwn(change.value, 'checklist'));
  db.close();
});

test('list.delete emits authoritative puts for the list and every reassigned task', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('list.create', 'l1', { title: '清单' }, 'c1', 1)),
    entry(2, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c2', 2)),
    entry(3, 'dev-1', cmd('task.create', 't2', { title: 'b' }, 'c3', 3)),
    entry(4, 'dev-1', cmd('task.assignList', 't1', { listId: 'l1' }, 'c4', 4)),
    entry(5, 'dev-1', cmd('task.assignList', 't2', { listId: 'l1' }, 'c5', 5)),
  ]);
  const result = m.processIntegrationBatch([
    entry(6, 'dev-1', cmd('list.delete', 'l1', {}, 'c6', 6)),
  ]);
  const commit = loadJournalCommit(db, result.snapshot.manifest.snapshotVersion);

  assert.equal(commit.changeCount, 3);
  const byId = Object.fromEntries(commit.changes.map((c) => [c.entityId, c]));
  assert.equal(byId.l1.type, 'customList.put');
  assert.equal(byId.l1.value.lifecycle, 'deleted');
  assert.ok(byId.l1.value.deletedAt);
  assert.equal(byId.t1.type, 'task.put');
  assert.equal(byId.t1.value.listId, null);
  assert.equal(byId.t2.type, 'task.put');
  assert.equal(byId.t2.value.listId, null);
  db.close();
});

test('NOOP and REJECTED produce empty coverage commits that keep the version chain continuous', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const created = m.processIntegrationBatch([
    entry(10, 'dev-1', cmd('task.create', 't1', { title: 'stable' }, 'c1', 1)),
  ]);
  const stableHash = created.snapshot.manifest.stateHash;

  const noop = m.processIntegrationBatch([
    entry(20, 'dev-1', cmd('task.setTitle', 't1', { title: 'stable' }, 'c2', 2)),
  ]);
  assert.equal(noop.receipts[0].status, 'NOOP');
  const noopCommit = loadJournalCommit(db, noop.snapshot.manifest.snapshotVersion);
  assert.equal(noopCommit.changeCount, 0);
  assert.equal(noopCommit.stateHashAfter, stableHash);

  const rejected = m.processIntegrationBatch([
    entry(30, 'dev-1', cmd('task.unknownCommand', 't1', {}, 'c3', 3)),
  ]);
  assert.equal(rejected.receipts[0].status, 'REJECTED');
  const rejectedCommit = loadJournalCommit(db, rejected.snapshot.manifest.snapshotVersion);
  assert.equal(rejectedCommit.changeCount, 0);
  assert.equal(rejectedCommit.stateHashAfter, stableHash);

  // 版本链连续:1 → 2 → 3,stateHash 不变但 commit 必须存在
  assert.deepEqual(allCommits(db), [1, 2, 3]);
  assert.equal(noopCommit.parentVersion, 1);
  assert.equal(rejectedCommit.parentVersion, 2);
  db.close();
});

test('SEQUENCE_GAP produces neither snapshot nor journal commit', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const gapped = m.processIntegrationBatch([
    entry(10, 'dev-g', cmd('task.create', 't1', { title: 'gap' }, 'c2', 2)),
  ]);
  assert.equal(gapped.receipts[0].status, 'SEQUENCE_GAP');
  assert.equal(gapped.snapshot, null);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 0);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM change_commits').get().c, 0);
  db.close();
});

test('redelivery of terminal commands never duplicates journal commits', async () => {
  const entries = await loadFixtureInput();
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  m.processIntegrationBatch(withBatchId(entries));
  const replay = m.processIntegrationBatch(withBatchId(entries));
  assert.equal(replay.snapshot, null);

  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 1);
  assert.deepEqual(allCommits(db), [1]);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM change_items').get().c, 1);
  db.close();
});

test('every snapshot version from the journal start has exactly one commit with an existing parent', async () => {
  const entries = await loadFixtureInput();
  const db = openDatabase(':memory:');
  ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID, now: () => 1 });
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  m.processIntegrationBatch(withBatchId(entries)); // v2,real changes
  m.processIntegrationBatch([
    entry(508, 'dev-1', cmd('task.setTitle', 't1', { title: '旧' }, 'c-noop', 1)),
  ]); // v3,empty coverage
  m.processIntegrationBatch([
    entry(509, 'dev-1', cmd('goal.create', 'g1', { title: '目标' }, 'c-goal', 2)),
  ]); // v4,goal.put

  const snapshots = db
    .prepare(`
      SELECT version FROM snapshots
       WHERE version > (SELECT min_snapshot_version FROM sync_journal_meta WHERE singleton_id = 1)
       ORDER BY version
    `)
    .all()
    .map((r) => r.version);
  const commits = db
    .prepare('SELECT snapshot_version, parent_version FROM change_commits ORDER BY snapshot_version')
    .all();

  assert.deepEqual(commits.map((c) => c.snapshot_version), snapshots);
  for (const commit of commits) {
    assert.ok(commit.parent_version < commit.snapshot_version);
    if (commit.parent_version > 0) {
      const parent = db
        .prepare('SELECT version FROM snapshots WHERE version = ?')
        .get(commit.parent_version);
      assert.ok(parent, `commit ${commit.snapshot_version} parent ${commit.parent_version} must be a snapshot`);
    }
  }
  db.close();
});

test('reconstruction property: Snapshot(N) + Commit(N+1) == Snapshot(N+1) across a mixed history', async () => {
  const entries = await loadFixtureInput();
  const db = openDatabase(':memory:');
  ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID, now: () => 1 });
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  // 混合历史:多命令批、空 commit、五类实体、删除生命周期
  m.processIntegrationBatch(withBatchId(entries));
  m.processIntegrationBatch([
    entry(508, 'dev-1', cmd('task.setNote', 't1', { note: 'same' }, 'c-n1', 1)),
    entry(509, 'dev-1', cmd('task.setNote', 't1', { note: 'same' }, 'c-n2', 2)),
  ]); // 一个 APPLIED + 一个 NOOP → 部分变更
  m.processIntegrationBatch([
    entry(510, 'dev-1', cmd('journal.setText', 'j1', { text: '日记' }, 'c-j1', 3)),
    entry(511, 'dev-1', cmd('goal.create', 'g1', { title: '目标' }, 'c-g1', 4)),
    entry(512, 'dev-1', cmd('insight.upsert', 'i1', { payload: { score: 7 } }, 'c-i1', 5)),
    entry(513, 'dev-1', cmd('task.delete', 't1', {}, 'c-d1', 6)),
  ]);

  const commitRows = db
    .prepare('SELECT snapshot_version FROM change_commits ORDER BY snapshot_version')
    .all();
  assert.ok(commitRows.length >= 4, 'history must contain several commits');

  for (const { snapshot_version: version } of commitRows) {
    const commit = loadJournalCommit(db, version);
    const stateBefore = commit.parentVersion === 0
      ? emptyState()
      : snapshotState(db, commit.parentVersion);
    const stateAfter = snapshotState(db, version);

    const reconstructed = applyJournalCommit(stateBefore, commit);
    assert.deepEqual(
      reconstructed,
      stateAfter,
      `reconstruction must be exact for commit ${version}`,
    );
    assert.equal(
      canonicalStateHash(reconstructed),
      commit.stateHashAfter,
      `reconstructed hash must match commit ${version}`,
    );
    assert.equal(
      canonicalStateHash(reconstructed),
      db.prepare('SELECT state_hash FROM snapshots WHERE version = ?').get(version).state_hash,
    );
  }
  db.close();
});

test('journal insert failure rolls back snapshot, entities, receipts and progress atomically', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
  ]);
  const stateBefore = m.getState();

  // 破坏 journal 表:第二个 commit 的 change_items 写入必然失败
  db.prepare('DROP TABLE change_items').run();

  assert.throws(() =>
    m.processIntegrationBatch([
      entry(2, 'dev-1', cmd('task.setTitle', 't1', { title: 'b' }, 'c2', 2)),
    ]),
  );

  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM change_commits').get().c, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM processed_commands').get().c, 1);
  assert.deepEqual(m.getState(), stateBefore);
  assert.equal(m.getSnapshotVersion(), 1);
  assert.equal(m.getAcceptedSequence('dev-1'), 1);
  db.close();
});

test('reducer physically removing an entity aborts before any durable write', () => {
  const db = openDatabase(':memory:');
  const removingReducer = (state, command, seq) => {
    if (command.type === 'task.removePhysically') {
      const tasks = { ...state.tasks };
      delete tasks[command.aggregateId];
      return { state: { ...state, tasks }, receipt: { status: 'APPLIED' } };
    }
    return applyCommand(state, command, seq);
  };
  const m = createMaterializer({ db, applyCommand: removingReducer, serverInstanceId: SERVER_ID });

  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
  ]);

  assert.throws(() =>
    m.processIntegrationBatch([
      entry(2, 'dev-1', cmd('task.removePhysically', 't1', {}, 'c2', 2)),
    ]),
    /physically removed/,
  );
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 1);
  assert.deepEqual(allCommits(db), [1]);
  assert.ok(m.getState().tasks.t1);
  db.close();
});

test('bootstrap snapshot carries journal commit 1 and reconstruction holds from the empty state', () => {
  const db = openDatabase(':memory:');
  const manifest = ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID, now: () => 1 });

  const commit = loadJournalCommit(db, 1);
  assert.equal(commit.snapshotVersion, 1);
  assert.equal(commit.parentVersion, 0);
  assert.equal(commit.changeCount, 0);
  assert.equal(commit.stateHashAfter, manifest.stateHash);
  assert.deepEqual(applyJournalCommit(emptyState(), commit), snapshotState(db, 1));
  db.close();
});

test('receipt coverage recovery writes an empty journal commit for the coverage snapshot', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  const first = m.processIntegrationBatch([
    entry(10, 'dev-old', cmd('task.create', 't-old', { title: 'x' }, 'c-old-1', 1)),
  ]);
  insertReceipt(db, {
    commandId: 'c-old-noop',
    batchId: 'batch-old-2',
    deviceId: 'dev-old',
    clientSequence: 2,
    brokerSequence: 20,
    commandType: 'task.setTitle',
    aggregateId: 't-old',
    status: 'NOOP',
    snapshotVersion: null,
    processedAt: 2,
  });

  const recovered = ensureReceiptCoverageSnapshot(db, {
    serverInstanceId: SERVER_ID,
    now: () => 100,
  });
  const commit = loadJournalCommit(db, recovered.manifest.snapshotVersion);

  assert.equal(commit.snapshotVersion, 2);
  assert.equal(commit.parentVersion, 1);
  assert.equal(commit.changeCount, 0);
  assert.equal(commit.stateHashAfter, first.snapshot.manifest.stateHash);
  assert.equal(commit.brokerFromSequence, 20);
  assert.equal(commit.brokerToSequence, 20);
  db.close();
});

test('recovery checkpoint snapshot dual-writes an empty journal commit', () => {
  const db = openDatabase(':memory:');
  ensureBootstrapSnapshot(db, { serverInstanceId: 'srv-recovery-test', now: () => 100 });

  const result = publishRecoverySnapshot(db, {
    preRestoreHighWater: 10,
    serverInstanceId: 'srv-recovery-test',
    now: () => 200,
  });

  const commit = loadJournalCommit(db, result.snapshotVersion);
  assert.equal(commit.snapshotVersion, 11);
  assert.equal(commit.parentVersion, 1);
  assert.equal(commit.changeCount, 0);
  assert.equal(commit.stateHashAfter, result.stateHash);
  assert.deepEqual(applyJournalCommit(snapshotState(db, 1), commit), snapshotState(db, 11));
  db.close();
});

test('upgraded database starts the journal at the migration head without backfill', () => {
  // 模拟 pre-journal 历史:删除 004 表并回退 user_version,插入旧快照
  const db = openDatabase(':memory:');
  db.prepare('DROP TABLE change_items').run();
  db.prepare('DROP TABLE change_commits').run();
  db.prepare('DROP TABLE sync_journal_meta').run();
  db.pragma('user_version = 3');
  const insertSnapshot = db.prepare(`
    INSERT INTO snapshots
      (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
       state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
       created_at)
    VALUES (?, ?, ?, ?, 3, ?, ?, X'00', 1, 1, ?)
  `);
  insertSnapshot.run(1, 0, 1, 1, 'sha256:legacy-1', 'sha256:compressed-1', 1);
  insertSnapshot.run(2, 1, 2, 2, 'sha256:legacy-2', 'sha256:compressed-2', 2);
  insertSnapshot.run(3, 2, 3, 3, 'sha256:legacy-3', 'sha256:compressed-3', 3);
  db.prepare(`
    INSERT INTO latest_snapshot (singleton_id, version, state_hash)
    VALUES (1, 3, 'sha256:legacy-3')
  `).run();

  migrate(db); // 只重放 004
  assert.equal(getJournalMeta(db).minSnapshotVersion, 3);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM change_commits').get().c, 0);

  // 第一个新 commit 直接接在迁移 head 之后
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  const result = m.processIntegrationBatch([
    entry(4, 'dev-1', cmd('task.create', 't1', { title: 'new' }, 'c1', 1)),
  ]);
  assert.equal(result.snapshot.manifest.snapshotVersion, 4);
  const commit = loadJournalCommit(db, 4);
  assert.equal(commit.parentVersion, 3);
  assert.equal(commit.changeCount, 1);
  assert.deepEqual(allCommits(db), [4], 'pre-journal versions are not backfilled');
  db.close();
});

test('canonical task migration publishes a real task.put journal commit', () => {
  const db = openDatabase(':memory:');
  const legacyTask = { title: '旧任务', start: '2026-01-01T00:00:00.000Z', end: null };
  const legacyState = {
    tasks: { 'legacy-1': legacyTask },
    customLists: {}, journals: {}, goals: {}, insights: {},
  };
  const legacy = buildSnapshot({
    state: legacyState,
    snapshotVersion: 1,
    parentVersion: 0,
    serverInstanceId: SERVER_ID,
    brokerFromSequence: 0,
    brokerToSequence: 0,
    createdAt: new Date(100).toISOString(),
  });
  db.prepare(`
    INSERT INTO entities
      (entity_type, entity_id, lifecycle, payload_json, last_broker_sequence,
       created_at, updated_at, deleted_at)
    VALUES ('task', 'legacy-1', 'active', ?, 0, 0, 0, NULL)
  `).run(JSON.stringify(legacyTask));
  db.prepare(`
    INSERT INTO snapshots
      (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
       state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
       created_at)
    VALUES (1, 0, 0, 0, 3, ?, ?, ?, ?, ?, 100)
  `).run(
    legacy.stateHash,
    legacy.compressedHash,
    legacy.compressed,
    legacy.manifest.uncompressedBytes,
    legacy.manifest.compressedBytes,
  );
  db.prepare(`
    INSERT INTO latest_snapshot (singleton_id, version, state_hash)
    VALUES (1, 1, ?)
  `).run(legacy.stateHash);

  const result = migrateCanonicalTaskEntities(db, {
    serverInstanceId: SERVER_ID,
    now: () => 200,
  });
  assert.equal(result.changedTasks, 1);
  assert.equal(result.snapshotVersion, 2);

  const commit = loadJournalCommit(db, 2);
  assert.equal(commit.changeCount, 1);
  const change = commit.changes[0];
  assert.equal(change.type, 'task.put');
  assert.equal(change.entityId, 'legacy-1');
  assert.equal(change.entityBrokerSequence, 0);
  assert.deepEqual(change.value.schedule, { startAt: '2026-01-01T00:00:00.000Z', endAt: null });
  assert.equal(change.value.start, undefined, 'legacy root field must be gone from the delta');
  assert.equal(change.value.lifecycle, 'active');

  // reconstruction:legacy snapshot + 迁移 commit == canonical snapshot
  assert.deepEqual(applyJournalCommit(snapshotState(db, 1), commit), snapshotState(db, 2));
  assert.equal(canonicalStateHash(applyJournalCommit(snapshotState(db, 1), commit)), commit.stateHashAfter);
  db.close();
});

test('loadJournalCommit returns null for versions without a commit', () => {
  const db = openDatabase(':memory:');
  assert.equal(loadJournalCommit(db, 42), null);
  db.close();
});

test('applyJournalCommit rejects unknown change types instead of guessing', () => {
  assert.throws(
    () => applyJournalCommit(emptyState(), {
      changes: [
        { type: 'task.title.patch', entityType: 'task', entityId: 't1', value: { title: 'x' } },
      ],
    }),
    /unsupported journal change/,
  );
});

test('computeJournalChanges keeps a deterministic order across entity types', () => {
  const from = emptyState();
  const to = {
    ...from,
    insights: { i1: { text: 'i', lifecycle: 'active', deletedAt: null } },
    tasks: { t1: { title: 't', lifecycle: 'active', deletedAt: null } },
    customLists: { l1: { title: 'l', lifecycle: 'active', deletedAt: null } },
  };
  const changes = computeJournalChanges({ fromState: from, toState: to, brokerToSequence: 9 });
  assert.deepEqual(changes.map((c) => c.entityId), ['t1', 'l1', 'i1'], 'tasks → customLists → journals → goals → insights');
  for (const change of changes) {
    assert.equal(change.entityBrokerSequence, 9);
  }
});
