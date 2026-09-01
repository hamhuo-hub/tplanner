import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { openDatabase } from '../src/state/database.js';
import {
  createMaterializer,
  ensureBootstrapSnapshot,
  ensureReceiptCoverageSnapshot,
} from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';
import { insertReceipt } from '../src/state/receipts.js';

const SERVER_ID = 'srv-test-deterministic';

const fixturePath = (name) =>
  fileURLToPath(new URL(`../../sync-v3/protocol/v3/fixtures/reducer/${name}`, import.meta.url));

async function loadFixtures() {
  const [input, expectedState, expectedReceipts] = await Promise.all([
    readFile(fixturePath('sequence-01.input.json'), 'utf8'),
    readFile(fixturePath('sequence-01.expected-state.json'), 'utf8'),
    readFile(fixturePath('sequence-01.expected-receipts.json'), 'utf8'),
  ]);
  return {
    entries: JSON.parse(input).commands,
    expectedState: JSON.parse(expectedState),
    expectedReceipts: JSON.parse(expectedReceipts).receipts,
  };
}

// fixture 是扁平的 broker 命令流(无 client batch 信封);补上 batchId 满足回执表 NOT NULL。
const withBatchId = (entries) => entries.map((e, i) => ({ ...e, batchId: `batch-${i + 1}` }));

test('fresh V3 database gets one idempotent empty bootstrap snapshot', () => {
  const db = openDatabase(':memory:');
  const manifest = ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID, now: () => 1 });
  assert.equal(manifest.snapshotVersion, 1);
  assert.equal(db.prepare('SELECT version FROM latest_snapshot').get().version, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM publication_outbox').get().c, 1);
  assert.equal(ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID }), null);
  assert.equal(createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID }).getSnapshotVersion(), 1);
  db.close();
});

test('lease fencing aborts an authoritative batch before any durable write', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({
    db,
    applyCommand,
    serverInstanceId: SERVER_ID,
    assertWriterLease: () => {
      throw new Error('LEASE_LOST');
    },
  });

  assert.throws(() => m.processIntegrationBatch([{
    brokerSequence: 1,
    deviceId: 'fenced-device',
    batchId: 'fenced-batch',
    command: {
      commandId: 'fenced-command',
      clientSequence: 1,
      type: 'task.create',
      aggregateId: 'fenced-task',
      arguments: { title: 'must not commit' },
    },
  }]), /LEASE_LOST/);

  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM processed_commands').get().c, 0);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM entities').get().c, 0);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 0);
  assert.equal(m.getSnapshotVersion(), 0);
  assert.deepEqual(m.getState(), {
    tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {},
  });
  db.close();
});

test('replays the fixture command stream to the expected state and receipts', async () => {
  const { entries, expectedState, expectedReceipts } = await loadFixtures();
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const result = m.processIntegrationBatch(withBatchId(entries));

  assert.deepEqual(m.getState(), expectedState);
  assert.deepEqual(
    result.receipts.map((r) => ({ brokerSequence: r.brokerSequence, status: r.status })),
    expectedReceipts,
  );

  assert.ok(result.snapshot, 'a snapshot must be generated');
  assert.equal(result.snapshot.manifest.snapshotVersion, 1);
  assert.equal(result.snapshot.manifest.parentVersion, 0);
  // broker 序列只进信封(用于审计),不进 manifest
  assert.equal(result.snapshot.envelope.brokerFromSequence, 501);
  assert.equal(result.snapshot.envelope.brokerToSequence, 507);

  // 单事务落库:回执 + 实体 + 快照 + latest + 发布 outbox
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM processed_commands').get().c, entries.length);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM publication_outbox').get().c, 1);
  assert.equal(db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get().version, 1);

  // 实体行把 lifecycle/deletedAt 拆成列,payload 不带生命周期字段
  const entityRow = db
    .prepare("SELECT * FROM entities WHERE entity_type = 'task' AND entity_id = 'task-1'")
    .get();
  assert.equal(entityRow.lifecycle, 'active');
  assert.equal(entityRow.deleted_at, null);
  const payload = JSON.parse(entityRow.payload_json);
  assert.equal(payload.lifecycle, undefined);
  assert.equal(payload.deletedAt, undefined);
  assert.equal(payload.title, '新标题');

  db.close();
});

test('reprocessing the same commands is idempotent and emits no snapshot', async () => {
  const { entries } = await loadFixtures();
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const first = m.processIntegrationBatch(withBatchId(entries));
  const second = m.processIntegrationBatch(withBatchId(entries));

  assert.equal(second.snapshot, null);
  assert.equal(second.receipts.length, first.receipts.length);
  for (const r of second.receipts) {
    const original = first.receipts.find((x) => x.commandId === r.commandId);
    assert.equal(r.status, original.status, `replayed ${r.commandId} must keep original status`);
  }
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM processed_commands').get().c, entries.length);
  db.close();
});

test('new terminal receipts publish coverage snapshots even when stateHash is unchanged', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  const entry = (brokerSequence, clientSequence, commandId, type, args = {}) => ({
    brokerSequence,
    deviceId: 'dev-coverage',
    batchId: `batch-${clientSequence}`,
    command: {
      commandId,
      clientSequence,
      type,
      aggregateId: 'task-coverage',
      arguments: args,
    },
  });

  const created = m.processIntegrationBatch([
    entry(10, 1, 'coverage-create', 'task.create', { title: 'stable' }),
  ]);
  const hashBefore = created.snapshot.manifest.stateHash;

  const cases = [
    ['NOOP', entry(20, 2, 'coverage-noop', 'task.setTitle', { title: 'stable' })],
    ['ID_ALREADY_EXISTS', entry(30, 3, 'coverage-exists', 'task.create', { title: 'other' })],
    ['REJECTED', entry(40, 4, 'coverage-schema', 'task.unknownCommand')],
  ];
  for (const [expectedStatus, commandEntry] of cases) {
    const result = m.processIntegrationBatch([commandEntry]);
    assert.equal(result.receipts[0].status, expectedStatus);
    assert.ok(result.snapshot, `${expectedStatus} must publish receipt coverage`);
    assert.equal(result.snapshot.manifest.stateHash, hashBefore);
    assert.equal(result.snapshot.envelope.brokerToSequence, commandEntry.brokerSequence);
    const stored = db
      .prepare('SELECT snapshot_version FROM processed_commands WHERE command_id = ?')
      .get(commandEntry.command.commandId);
    assert.equal(stored.snapshot_version, result.snapshot.manifest.snapshotVersion);
  }

  const deleted = m.processIntegrationBatch([
    entry(50, 5, 'coverage-delete', 'task.delete'),
  ]);
  const deletedHash = deleted.snapshot.manifest.stateHash;
  const staleEdit = m.processIntegrationBatch([
    entry(60, 6, 'coverage-deleted-edit', 'task.setNote', { note: 'must not revive' }),
  ]);
  assert.equal(staleEdit.receipts[0].status, 'ENTITY_DELETED');
  assert.equal(staleEdit.snapshot.manifest.stateHash, deletedHash);
  assert.equal(staleEdit.snapshot.envelope.brokerToSequence, 60);

  // All messages in this redelivery were already terminal before this call.
  const replay = m.processIntegrationBatch([
    entry(60, 6, 'coverage-deleted-edit', 'task.setNote', { note: 'must not revive' }),
  ]);
  assert.equal(replay.snapshot, null);
  db.close();
});

test('SCHEMA_UNSUPPORTED terminal status also receives snapshot coverage', () => {
  const db = openDatabase(':memory:');
  const unsupportedReducer = (state) => ({
    state,
    receipt: { status: 'SCHEMA_UNSUPPORTED', errorCode: 'SCHEMA_UNSUPPORTED' },
  });
  const m = createMaterializer({ db, applyCommand: unsupportedReducer, serverInstanceId: SERVER_ID });
  const result = m.processIntegrationBatch([{
    brokerSequence: 70,
    deviceId: 'dev-schema',
    batchId: 'batch-schema',
    command: {
      commandId: 'coverage-schema-status',
      clientSequence: 1,
      type: 'future.command',
      aggregateId: 'future-1',
      arguments: {},
    },
  }]);
  assert.equal(result.receipts[0].status, 'SCHEMA_UNSUPPORTED');
  assert.equal(result.snapshot.envelope.brokerToSequence, 70);
  db.close();
});

test('restart creates one idempotent coverage snapshot for legacy null-version receipts', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  const first = m.processIntegrationBatch([{
    brokerSequence: 10,
    deviceId: 'dev-old',
    batchId: 'batch-old-1',
    command: {
      commandId: 'old-create',
      clientSequence: 1,
      type: 'task.create',
      aggregateId: 'old-task',
      arguments: { title: 'unchanged across recovery' },
    },
  }]);
  insertReceipt(db, {
    commandId: 'old-noop',
    batchId: 'batch-old-2',
    deviceId: 'dev-old',
    clientSequence: 2,
    brokerSequence: 20,
    commandType: 'task.setTitle',
    aggregateId: 'old-task',
    status: 'NOOP',
    snapshotVersion: null,
    processedAt: 2,
  });
  // A later transient gap is deliberately not part of terminal coverage.
  insertReceipt(db, {
    commandId: 'old-gap',
    batchId: 'batch-old-3',
    deviceId: 'dev-old',
    clientSequence: 3,
    brokerSequence: 30,
    commandType: 'task.setTitle',
    aggregateId: 'old-task',
    status: 'SEQUENCE_GAP',
    snapshotVersion: null,
    processedAt: 3,
  });

  const recovered = ensureReceiptCoverageSnapshot(db, {
    serverInstanceId: SERVER_ID,
    now: () => 100,
  });
  assert.equal(recovered.manifest.snapshotVersion, 2);
  assert.equal(recovered.manifest.stateHash, first.snapshot.manifest.stateHash);
  assert.equal(recovered.envelope.brokerFromSequence, 20);
  assert.equal(recovered.envelope.brokerToSequence, 20);
  assert.equal(
    db.prepare("SELECT snapshot_version FROM processed_commands WHERE command_id = 'old-noop'").get().snapshot_version,
    2,
  );
  assert.equal(
    db.prepare("SELECT snapshot_version FROM processed_commands WHERE command_id = 'old-gap'").get().snapshot_version,
    null,
  );
  assert.equal(ensureReceiptCoverageSnapshot(db, { serverInstanceId: SERVER_ID }), null);
  assert.equal(createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID }).getSnapshotVersion(), 2);
  db.close();
});

test('same stream replayed in a fresh database yields the same stateHash', async () => {
  const { entries } = await loadFixtures();
  const run = () => {
    const db = openDatabase(':memory:');
    const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
    const { snapshot } = m.processIntegrationBatch(withBatchId(entries));
    const hash = snapshot.manifest.stateHash;
    db.close();
    return hash;
  };
  assert.equal(run(), run());
});

test('restarts resume state and snapshot version from the entities table', async () => {
  const { entries, expectedState } = await loadFixtures();
  const db = openDatabase(':memory:');

  const m1 = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  m1.processIntegrationBatch(withBatchId(entries));

  // 新实例模拟进程重启:内存状态从 entities 表重建,快照版本从 snapshots 表续接
  const m2 = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  assert.deepEqual(m2.getState(), expectedState);
  assert.equal(m2.getSnapshotVersion(), 1);

  const result = m2.processIntegrationBatch([
    {
      brokerSequence: 508,
      deviceId: 'phone-0198f200',
      batchId: 'batch-next',
      command: {
        commandId: '0198f2a1-0108-7000-8000-000000000108',
        clientSequence: 5,
        type: 'task.setTitle',
        aggregateId: 'task-1',
        arguments: { title: '重启后的标题' },
      },
    },
  ]);

  assert.equal(result.snapshot.manifest.snapshotVersion, 2);
  assert.equal(result.snapshot.manifest.parentVersion, 1);
  assert.equal(m2.getState().tasks['task-1'].title, '重启后的标题');
  db.close();
});

test('unknown command type is rejected with a receipt but does not block the stream', async () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const result = m.processIntegrationBatch([
    {
      brokerSequence: 600,
      deviceId: 'dev-x',
      batchId: 'batch-x',
      command: {
        commandId: 'cmd-unknown',
        clientSequence: 1,
        type: 'task.toggleSomething',
        aggregateId: 'task-1',
        arguments: {},
      },
    },
    {
      brokerSequence: 601,
      deviceId: 'dev-x',
      batchId: 'batch-x',
      command: {
        commandId: 'cmd-create',
        clientSequence: 2,
        type: 'task.create',
        aggregateId: 'task-1',
        arguments: { title: '后续命令不受影响' },
      },
    },
  ]);

  assert.equal(result.receipts[0].status, 'REJECTED');
  assert.equal(result.receipts[0].errorCode, 'SCHEMA_UNSUPPORTED');
  assert.equal(result.receipts[1].status, 'APPLIED');
  assert.equal(result.snapshot.manifest.snapshotVersion, 1);
  assert.ok(m.getState().tasks['task-1']);
  db.close();
});

test('sequence gap is rejected with SEQUENCE_GAP until the client retransmits the missing batch', async () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const mk = (commandId, clientSequence, aggregateId) => ({
    brokerSequence: 0,
    deviceId: 'dev-g',
    batchId: 'b',
    command: {
      commandId,
      clientSequence,
      type: 'task.create',
      aggregateId,
      arguments: { title: aggregateId },
    },
  });

  // 批次 1 丢失,直接来批次 2(seq 2/3)→ 缺口拒绝,不推进 accepted
  const gapped = m.processIntegrationBatch([
    { ...mk('c2', 2, 't-1'), brokerSequence: 10 },
    { ...mk('c3', 3, 't-2'), brokerSequence: 11 },
  ]);
  assert.equal(gapped.receipts[0].status, 'SEQUENCE_GAP');
  assert.equal(gapped.receipts[1].status, 'SEQUENCE_GAP');
  assert.equal(gapped.snapshot, null);
  assert.equal(m.getState().tasks['t-1'], undefined);
  assert.equal(m.getAcceptedSequence('dev-g'), 0);

  // 即使同一个 integration batch 因其他设备的终态命令发布了快照，GAP 也不能
  // 借用那个版本伪装为已覆盖的终态 receipt。
  const mixed = m.processIntegrationBatch([
    { ...mk('c2-mixed', 2, 't-mixed'), brokerSequence: 15 },
    {
      brokerSequence: 16,
      deviceId: 'dev-other',
      batchId: 'b-other',
      command: {
        commandId: 'other-create',
        clientSequence: 1,
        type: 'task.create',
        aggregateId: 't-other',
        arguments: { title: 'other' },
      },
    },
  ]);
  assert.ok(mixed.snapshot);
  assert.equal(mixed.snapshot.envelope.brokerToSequence, 16);
  assert.equal(
    db.prepare("SELECT snapshot_version FROM processed_commands WHERE command_id = 'c2-mixed'").get().snapshot_version,
    null,
  );

  // 客户端重传完整批次 1+2(seq 1/2/3):缺口补齐后全部重裁应用
  const retried = m.processIntegrationBatch([
    { ...mk('c1', 1, 't-1'), brokerSequence: 12 },
    { ...mk('c2', 2, 't-2'), brokerSequence: 13 },
    { ...mk('c3', 3, 't-3'), brokerSequence: 14 },
  ]);
  assert.deepEqual(retried.receipts.map((r) => r.status), ['APPLIED', 'APPLIED', 'APPLIED']);
  assert.ok(m.getState().tasks['t-1']);
  assert.ok(m.getState().tasks['t-2']);
  assert.ok(m.getState().tasks['t-3']);
  assert.equal(retried.snapshot.manifest.snapshotVersion, 2);
  assert.equal(m.getAcceptedSequence('dev-g'), 3);

  // 旧 SEQUENCE_GAP 回执已被重写,库中不再残留
  const gaps = db
    .prepare("SELECT COUNT(*) AS c FROM processed_commands WHERE status = 'SEQUENCE_GAP'")
    .get().c;
  assert.equal(gaps, 0);
  db.close();
});

test('re-sending an accepted clientSequence returns the prior receipt, even with a different commandId', async () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const mk = (commandId, clientSequence, aggregateId) => ({
    deviceId: 'dev-d',
    batchId: 'b',
    command: {
      commandId,
      clientSequence,
      type: 'task.create',
      aggregateId,
      arguments: {},
    },
  });

  m.processIntegrationBatch([{ ...mk('orig', 1, 't-1'), brokerSequence: 1 }]);

  // 相同 commandId 重发:返回原回执,不重复执行
  const replay = m.processIntegrationBatch([{ ...mk('orig', 1, 't-1'), brokerSequence: 2 }]);
  assert.equal(replay.receipts[0].status, 'APPLIED');
  assert.equal(replay.snapshot, null);

  // 相同序列、不同 commandId:返回该序列的既有回执,不产生 UNIQUE 冲突、不新建实体
  const differentId = m.processIntegrationBatch([{ ...mk('other', 1, 't-2'), brokerSequence: 3 }]);
  assert.equal(differentId.receipts[0].commandId, 'orig');
  assert.equal(differentId.receipts[0].status, 'APPLIED');
  assert.equal(differentId.snapshot, null);
  assert.equal(m.getState().tasks['t-2'], undefined);
  db.close();
});
