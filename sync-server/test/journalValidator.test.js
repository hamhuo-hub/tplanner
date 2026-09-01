import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync, gzipSync } from 'node:zlib';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer, ensureBootstrapSnapshot } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';
import {
  JournalValidationError,
  validateJournalHead,
  validateJournalRange,
  validateJournalTail,
} from '../src/state/journalValidator.js';

const SERVER_ID = 'srv-validator-test';

const fixturePath = (name) =>
  fileURLToPath(new URL(`../../sync-v3/protocol/v3/fixtures/reducer/${name}`, import.meta.url));

async function loadFixtureInput() {
  return JSON.parse(await readFile(fixturePath('sequence-01.input.json'), 'utf8')).commands;
}

const withBatchId = (entries) => entries.map((e, i) => ({ ...e, batchId: `batch-${i + 1}` }));

function entry(brokerSequence, deviceId, command, batchId = 'b1') {
  return { brokerSequence, deviceId, batchId, command };
}

function cmd(type, aggregateId, args, commandId, clientSequence) {
  return { commandId, clientSequence, type, aggregateId, arguments: args ?? {} };
}

function latestVersion(db) {
  return db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get().version;
}

// 合成流:五类实体 + NOOP + REJECTED 覆盖一个批次(见 buildMixed)。
function withBatchIdEntries() {
  return [
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
    entry(2, 'dev-1', cmd('list.create', 'l1', { title: '清单' }, 'c2', 2)),
    entry(3, 'dev-1', cmd('task.assignList', 't1', { listId: 'l1' }, 'c3', 3)),
    entry(4, 'dev-1', cmd('journal.setText', 'j1', { text: '日记' }, 'c4', 4)),
    entry(5, 'dev-1', cmd('goal.create', 'g1', { title: '目标' }, 'c5', 5)),
    entry(6, 'dev-1', cmd('insight.upsert', 'i1', { payload: { score: 7 } }, 'c6', 6)),
    entry(7, 'dev-1', cmd('task.setTitle', 't1', { title: 'a' }, 'c7', 7)), // NOOP
    entry(8, 'dev-1', cmd('task.unknownCommand', 't1', {}, 'c8', 8)), // REJECTED
  ].map((e, i) => ({ ...e, batchId: `batch-${i + 1}` }));
}

// v1 bootstrap(空) → v2 真实变更 → v3 setNote APPLIED → v4 双实体
function buildMixed(db) {
  ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID, now: () => 1 });
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  m.processIntegrationBatch(withBatchIdEntries()); // v2
  m.processIntegrationBatch([
    entry(9, 'dev-1', cmd('task.setNote', 't1', { note: 'same' }, 'c9', 9)),
  ]); // v3:APPLIED(单实体)
  m.processIntegrationBatch([
    entry(10, 'dev-1', cmd('goal.create', 'g2', { title: '目标二' }, 'c10', 10)),
    entry(11, 'dev-1', cmd('journal.setText', 'j2', { text: '第二篇' }, 'c11', 11)),
  ]); // v4:两个实体
  return { m, head: latestVersion(db) };
}

test('full mixed chain from min snapshot reconstructs to head with every checkpoint hash matching', () => {
  const db = openDatabase(':memory:');
  const { head } = buildMixed(db);

  const result = validateJournalHead(db);
  assert.equal(result.ok, true);
  assert.equal(result.fromSnapshotVersion, 0);
  assert.equal(result.toSnapshotVersion, head);
  assert.equal(result.validatedCommits, head);
  assert.equal(
    result.headStateHash,
    db.prepare('SELECT state_hash FROM snapshots WHERE version = ?').get(head).state_hash,
  );
  db.close();
});

test('partial range validation passes and empty commits are legal', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  const result = validateJournalRange(db, { fromSnapshotVersion: 2, toSnapshotVersion: 3 });
  assert.equal(result.ok, true);
  assert.equal(result.validatedCommits, 1);
  db.close();
});

test('fresh database without any snapshot validates as an empty chain', () => {
  const db = openDatabase(':memory:');
  const result = validateJournalHead(db);
  assert.equal(result.ok, true);
  assert.equal(result.fromSnapshotVersion, 0);
  assert.equal(result.toSnapshotVersion, 0);
  assert.equal(result.validatedCommits, 0);
  db.close();
});

function expectValidationFailure(db, code) {
  assert.throws(
    () => validateJournalHead(db),
    (err) => err instanceof JournalValidationError && err.code === code,
    `expected ${code}`,
  );
}

test('a missing commit fails closed with JOURNAL_COMMIT_MISSING', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  db.prepare('DELETE FROM change_commits WHERE snapshot_version = 3').run();
  expectValidationFailure(db, 'JOURNAL_COMMIT_MISSING');
  db.close();
});

test('a tampered parentVersion fails closed with JOURNAL_PARENT_MISMATCH', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  db.prepare('UPDATE change_commits SET parent_version = parent_version - 1 WHERE snapshot_version = 4').run();
  expectValidationFailure(db, 'JOURNAL_PARENT_MISMATCH');
  db.close();
});

test('a tampered stateHashAfter fails closed with JOURNAL_HASH_MISMATCH', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  db.prepare("UPDATE change_commits SET state_hash_after = 'sha256:deadbeef' WHERE snapshot_version = 3").run();
  expectValidationFailure(db, 'JOURNAL_HASH_MISMATCH');
  db.close();
});

test('a tampered change payload fails closed with JOURNAL_HASH_MISMATCH', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  const item = db
    .prepare("SELECT snapshot_version, ordinal, payload_json FROM change_items WHERE change_type = 'task.put' ORDER BY snapshot_version LIMIT 1")
    .get();
  const payload = JSON.parse(item.payload_json);
  payload.title = 'tampered title';
  db.prepare('UPDATE change_items SET payload_json = ? WHERE snapshot_version = ? AND ordinal = ?')
    .run(JSON.stringify(payload), item.snapshot_version, item.ordinal);
  expectValidationFailure(db, 'JOURNAL_HASH_MISMATCH');
  db.close();
});

test('a tampered snapshot state_hash column fails closed with SNAPSHOT_HASH_MISMATCH', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  // 中间版本:重建 hash 与存储的 state_hash 列失配。
  db.prepare("UPDATE snapshots SET state_hash = 'sha256:wrong' WHERE version = 3").run();
  expectValidationFailure(db, 'SNAPSHOT_HASH_MISMATCH');
  db.close();
});

test('a tampered base snapshot payload fails closed with SNAPSHOT_HASH_MISMATCH', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  // checkpoint 载荷被换掉(state_hash 列不动):validator 对 base 会解压并重算 hash。
  const row = db.prepare('SELECT compressed_payload FROM snapshots WHERE version = 2').get();
  const envelope = JSON.parse(gunzipSync(row.compressed_payload).toString('utf8'));
  envelope.state.tasks.injected = { title: 'x', lifecycle: 'active', deletedAt: null };
  db.prepare('UPDATE snapshots SET compressed_payload = ? WHERE version = 2')
    .run(gzipSync(JSON.stringify(envelope)));
  assert.throws(
    () => validateJournalRange(db, { fromSnapshotVersion: 2, toSnapshotVersion: 3 }),
    (err) => err instanceof JournalValidationError && err.code === 'SNAPSHOT_HASH_MISMATCH',
  );
  db.close();
});

test('an uninstallable change type fails closed with JOURNAL_CHANGE_INVALID', () => {
  const db = openDatabase(':memory:');
  const { m } = buildMixed(db);

  // 追加一个真正的 NOOP 空 commit,再注入未知 change type。
  m.processIntegrationBatch([
    entry(12, 'dev-1', cmd('task.setNote', 't1', { note: 'same' }, 'c12', 12)),
  ]);
  const emptyVersion = latestVersion(db);
  db.prepare(`
    INSERT INTO change_items
      (snapshot_version, ordinal, change_type, entity_type, entity_id, entity_broker_sequence, payload_json)
    VALUES (?, 0, 'task.patch', 'task', 't-x', 1, '{}')
  `).run(emptyVersion);
  expectValidationFailure(db, 'JOURNAL_CHANGE_INVALID');
  db.close();
});

test('a missing base snapshot fails closed with JOURNAL_BASE_SNAPSHOT_MISSING', () => {
  const db = openDatabase(':memory:');
  buildMixed(db);

  assert.throws(
    () => validateJournalRange(db, { fromSnapshotVersion: 7, toSnapshotVersion: 9 }),
    (err) => err instanceof JournalValidationError && err.code === 'JOURNAL_BASE_SNAPSHOT_MISSING',
  );
  db.close();
});

test('invalid ranges are rejected as usage errors, not journal corruption', () => {
  const db = openDatabase(':memory:');
  assert.throws(
    () => validateJournalRange(db, { fromSnapshotVersion: 5, toSnapshotVersion: 2 }),
    /invalid journal range/,
  );
  assert.throws(
    () => validateJournalRange(db, { fromSnapshotVersion: -1, toSnapshotVersion: 0 }),
    /invalid journal range/,
  );
  db.close();
});

test('tail validation extends a previously validated checkpoint and rejects a wrong base', () => {
  const db = openDatabase(':memory:');
  const { m } = buildMixed(db);

  const checkpoint = validateJournalHead(db);
  m.processIntegrationBatch([
    entry(20, 'dev-1', cmd('task.setNote', 't1', { note: '尾巴' }, 'c20', 12)),
  ]);
  const head = latestVersion(db);
  assert.equal(head, checkpoint.toSnapshotVersion + 1);

  const tail = validateJournalTail(db, {
    fromSnapshotVersion: checkpoint.toSnapshotVersion,
    toSnapshotVersion: head,
    baseState: checkpoint.headState,
  });
  assert.equal(tail.ok, true);
  assert.equal(tail.validatedCommits, 1);
  assert.equal(tail.toSnapshotVersion, head);
  assert.equal(
    tail.headStateHash,
    db.prepare('SELECT state_hash FROM snapshots WHERE version = ?').get(head).state_hash,
  );

  // 错误 baseState(空状态)必然在第一处 checkpoint 失配
  assert.throws(
    () => validateJournalTail(db, {
      fromSnapshotVersion: checkpoint.toSnapshotVersion,
      toSnapshotVersion: head,
      baseState: { tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {} },
    }),
    (err) => err instanceof JournalValidationError && err.code === 'JOURNAL_HASH_MISMATCH',
  );
  assert.throws(
    () => validateJournalTail(db, {
      fromSnapshotVersion: checkpoint.toSnapshotVersion,
      toSnapshotVersion: head,
    }),
    /requires the previously validated baseState/,
  );
  db.close();
});

test('110+ mixed commits reconstruct continuously from min to head', async () => {
  const db = openDatabase(':memory:');
  ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID, now: () => 1 });
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const fixture = await loadFixtureInput();
  m.processIntegrationBatch(withBatchId(fixture));

  // dev-1 的独立序列:先建一个稳定任务,再 110 个单命令 commit,每 10 个穿插 NOOP 空 commit。
  m.processIntegrationBatch([
    entry(1000, 'dev-1', cmd('task.create', 't-fixed', { title: 'stable' }, 'c-fixed', 1)),
  ]);
  let brokerSeq = 1001;
  let clientSeq = 2;
  for (let i = 0; i < 110; i += 1) {
    const command = (i % 10 === 0)
      ? cmd('task.setTitle', 't-fixed', { title: 'stable' }, `c-${i}-noop`, clientSeq)
      : cmd('task.create', `t-${i}`, { title: `task ${i}` }, `c-${i}`, clientSeq);
    m.processIntegrationBatch([entry(brokerSeq, 'dev-1', command)]);
    brokerSeq += 1;
    clientSeq += 1;
  }
  const head = latestVersion(db);
  assert.ok(head > 110, 'history must exceed 110 commits');

  const result = validateJournalHead(db);
  assert.equal(result.ok, true);
  assert.equal(result.fromSnapshotVersion, 0);
  assert.equal(result.toSnapshotVersion, head);
  assert.equal(result.validatedCommits, head);
  assert.equal(
    result.headStateHash,
    db.prepare('SELECT state_hash FROM snapshots WHERE version = ?').get(head).state_hash,
  );
  db.close();
});
