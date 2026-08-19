import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/state/database.js';
import { parseLegacyData, importIntoDatabase } from '../src/state/v1Importer.js';
import {
  createLegacyAdapter,
  diffEventsToCommands,
  diffGoalsToCommands,
  diffInsightsToCommands,
  diffJournalsToCommands,
  LEGACY_DEVICE_ID,
  projectLegacy,
} from '../src/api/legacyCompat.js';
import { createMaterializer, loadStateFromDb } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';
import { buildServer } from '../src/api/app.js';

// 用真实 materializer 扮演 broker+整合器:发布即同步整合(测试用)
function fakePublisher(db, materializer) {
  let seq = 0;
  return {
    publish: async (batch) => {
      const entries = batch.commands.map((command, i) => ({
        brokerSequence: seq + i + 1,
        deviceId: batch.deviceId,
        batchId: batch.batchId,
        command,
      }));
      seq += batch.commands.length;
      materializer.processIntegrationBatch(entries);
      return { batchId: batch.batchId, brokerSequence: seq, state: 'BROKER_PERSISTED', duplicate: false };
    },
  };
}

const legacySample = {
  events: [
    { id: 'task-1', payload: { title: '旧标题', note: '', groupId: 'g1', itemType: 'task' }, updatedAt: 1000, deletedAt: null },
    { id: 'task-2', payload: { title: '已删' }, updatedAt: 2000, deletedAt: 3000 },
  ],
  journals: { '2026-08-19': { text: '日记', updatedAt: 4000, deletedAt: null } },
  goals: [{ id: 'goal-1', payload: { title: '目标' }, updatedAt: 5000, deletedAt: null }],
  insights: { entries: [{ id: 'ins-1', payload: { text: '想法' } }], reports: { '2026-08-19': { emotion: 'calm' } } },
};

function seededDb() {
  const db = openDatabase(':memory:');
  const { entities } = parseLegacyData(legacySample);
  importIntoDatabase(db, entities);
  return db;
}

// ── 投影 ──────────────────────────────────────────────────────────────────

test('projection round-trips legacy data with groupId stripped and tombstones in ms', () => {
  const db = seededDb();
  const proj = projectLegacy(db);

  assert.equal(proj.events.length, 2);
  const t1 = proj.events.find((e) => e.id === 'task-1');
  assert.equal('groupId' in t1.payload, false);
  assert.equal(t1.deletedAt, null);

  const t2 = proj.events.find((e) => e.id === 'task-2');
  assert.equal(t2.deletedAt, t2.updatedAt, 'deletedAt projects as real epoch ms, not broker sequence');
  assert.ok(t2.deletedAt > 0);

  assert.equal(proj.journals['2026-08-19'].text, '日记');
  assert.equal(proj.goals.length, 1);
  assert.equal(proj.insights.entries.length, 1);
  assert.equal(proj.insights.reports['2026-08-19'].emotion, 'calm');
  db.close();
});

// ── diff → 命令 ───────────────────────────────────────────────────────────

test('diffEventsToCommands maps create/update/delete/restore/noop', () => {
  const db = seededDb();
  const state = loadStateFromDb(db);

  // 全新实体 → create + 字段命令
  const created = diffEventsToCommands(state, [
    { id: 'task-new', payload: { title: '新', note: 'n', completed: true } },
  ]);
  assert.deepEqual(created.map((c) => c.type), ['task.create', 'task.setNote', 'task.setCompleted']);

  // 单字段变化 → 单条命令
  const titleOnly = diffEventsToCommands(state, [
    { id: 'task-1', payload: { title: '新标题', note: '', itemType: 'task' }, deletedAt: null },
  ]);
  assert.deepEqual(titleOnly.map((c) => c.type), ['task.setTitle']);

  // 无变化 → 空
  const noop = diffEventsToCommands(state, [
    { id: 'task-1', payload: { title: '旧标题', note: '', itemType: 'task' }, deletedAt: null },
  ]);
  assert.equal(noop.length, 0);

  // 删除与恢复
  assert.deepEqual(
    diffEventsToCommands(state, [{ id: 'task-1', payload: {}, deletedAt: 123 }]).map((c) => c.type),
    ['task.delete'],
  );
  assert.deepEqual(
    diffEventsToCommands(state, [{ id: 'task-2', payload: { title: '已删' }, deletedAt: null }]).map((c) => c.type),
    ['task.restore'],
  );
  db.close();
});

test('diffJournalsToCommands handles create/update/delete and skips revive', () => {
  const db = seededDb();
  const state = loadStateFromDb(db);

  const created = diffJournalsToCommands(state, { '2026-08-20': { text: '新日记' } });
  assert.deepEqual(created.map((c) => c.type), ['journal.setText']);

  const updated = diffJournalsToCommands(state, { '2026-08-19': { text: '改' } });
  assert.deepEqual(updated.map((c) => c.type), ['journal.setText']);

  const deleted = diffJournalsToCommands(state, { '2026-08-19': { text: 'x', deletedAt: 1 } });
  assert.deepEqual(deleted.map((c) => c.type), ['journal.delete']);

  const noop = diffJournalsToCommands(state, { '2026-08-19': { text: '日记' } });
  assert.equal(noop.length, 0);
  db.close();
});

test('diffGoalsToCommands maps create+patch and delete', () => {
  const db = seededDb();
  const state = loadStateFromDb(db);

  const created = diffGoalsToCommands(state, [{ id: 'goal-2', payload: { title: '新目标', progress: 1 } }]);
  assert.deepEqual(created.map((c) => c.type), ['goal.create', 'goal.patch']);

  const patched = diffGoalsToCommands(state, [{ id: 'goal-1', payload: { title: '目标', progress: 9 } }]);
  assert.deepEqual(patched.map((c) => c.type), ['goal.patch']);
  assert.deepEqual(patched[0].arguments.patch, { progress: 9 });

  const deleted = diffGoalsToCommands(state, [{ id: 'goal-1', payload: {}, deletedAt: 1 }]);
  assert.deepEqual(deleted.map((c) => c.type), ['goal.delete']);
  db.close();
});

test('diffInsightsToCommands maps upsert and report upsert', () => {
  const db = seededDb();
  const state = loadStateFromDb(db);

  const changed = diffInsightsToCommands(state, {
    entries: [{ id: 'ins-1', payload: { text: '改' } }],
    reports: { '2026-08-20': { emotion: 'happy' } },
  });
  assert.deepEqual(changed.map((c) => c.type), ['insight.upsert', 'insight.upsert']);
  assert.equal(changed[1].aggregateId, 'report-2026-08-20');
  db.close();
});

// ── 适配器端到端(真实 materializer 作 broker)──────────────────────────────

function seededAdapter() {
  const db = seededDb();
  const materializer = createMaterializer({ db, applyCommand, serverInstanceId: 'srv-legacy-test' });
  const publisher = fakePublisher(db, materializer);
  const legacy = createLegacyAdapter({ db, publisher, log: { warn: () => {} } });
  return { db, legacy };
}

test('putEvents publishes one batch with continuous clientSequence and updates projection', async () => {
  const { db, legacy } = seededAdapter();

  const before = await legacy.getEvents();
  const result = await legacy.putEvents([
    { id: 'task-1', payload: { title: '改过的标题', note: '', itemType: 'task' }, deletedAt: null },
    { id: 'task-new', payload: { title: '全新', itemType: 'task' } },
  ]);

  assert.equal(result.length, 3, 'projection now has three tasks');
  assert.equal(result.find((e) => e.id === 'task-1').payload.title, '改过的标题');
  assert.ok(result.some((e) => e.id === 'task-new'));
  assert.equal(before.length, 2);

  // 第二次 PUT 序列必须从上次之后继续(材料器要求严格连续)
  await legacy.putEvents([{ id: 'task-3', payload: { title: '第三个' } }]);
  const seqs = db
    .prepare('SELECT client_sequence FROM processed_commands WHERE device_id = ? ORDER BY client_sequence')
    .all(LEGACY_DEVICE_ID)
    .map((r) => r.client_sequence);
  assert.deepEqual(seqs, [...seqs.keys()].map((i) => i + 1), 'clientSequence is continuous across PUTs');
  db.close();
});

test('changes long-poll wakes on a new snapshot revision', async () => {
  const { db, legacy } = seededAdapter();

  const row = db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get();
  assert.equal(row, undefined, 'no snapshot yet before the first integration');

  const wake = legacy.changes({ since: 0, maxWaitMs: 5_000 });
  await legacy.putEvents([{ id: 'task-x', payload: { title: 'x' } }]);
  const res = await wake;
  assert.ok(res.revision >= 1);
  assert.deepEqual(res.datasets, ['events', 'journals', 'goals', 'insights']);

  const quiet = await legacy.changes({ since: res.revision, maxWaitMs: 120, pollMs: 50 });
  assert.equal(quiet.revision, res.revision);
  assert.deepEqual(quiet.datasets, []);
  db.close();
});

test('legacy routes are wired when the adapter is injected', async () => {
  const { legacy } = seededAdapter();
  const app = buildServer({
    publisher: { publish: async () => ({}) },
    validateBatch: () => null,
    store: {
      capabilities: () => ({}),
      acceptedThrough: () => 0,
      receiptsForDevice: () => [],
      latestSnapshotMeta: () => null,
      snapshotMeta: () => null,
      snapshotPayload: () => null,
      recordSnapshotAck: () => {},
    },
    health: { readiness: async () => ({ ok: true }), status: async () => ({}) },
    legacy,
  });

  const events = await app.inject({ method: 'GET', url: '/tplanner/events' });
  assert.equal(events.statusCode, 200);
  assert.equal(events.json().length, 2);

  const put = await app.inject({
    method: 'PUT',
    url: '/tplanner/events',
    payload: [{ id: 'task-1', payload: { title: '路由写入', note: '', itemType: 'task' }, deletedAt: null }],
  });
  assert.equal(put.statusCode, 200);
  assert.equal(put.json().find((e) => e.id === 'task-1').payload.title, '路由写入');

  const time = await app.inject({ method: 'GET', url: '/tplanner/time' });
  assert.ok(time.json().time > 0);
});
