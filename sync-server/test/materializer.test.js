import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';

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
