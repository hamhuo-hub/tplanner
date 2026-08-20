import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseLegacyData, importIntoDatabase } from '../src/state/v1Importer.js';
import { openDatabase } from '../src/state/database.js';

const legacy = {
  events: [
    {
      id: 'task-1',
      payload: { title: '开会', note: '', groupId: 'g-1', timezone: 'Asia/Shanghai' },
      updatedAt: 1700000001000,
      deletedAt: null,
    },
    {
      id: 'task-2',
      payload: { title: '已删', groupId: 'g-1' },
      updatedAt: 1700000002000,
      deletedAt: 1700000003000,
    },
  ],
  journals: {
    '2026-08-19': { text: '今天不错', updatedAt: 1700000004000, deletedAt: null },
    '2026-08-18': '旧版纯字符串',
  },
  goals: [{ id: 'goal-1', payload: { title: '读完一本书' }, updatedAt: 1700000005000, deletedAt: null }],
  insights: {
    entries: [{ id: 'ins-1', payload: { text: '想法' }, updatedAt: 1700000006000, deletedAt: null }],
    reports: { '2026-08-19': { emotion: 'calm', updatedAt: 1700000007000 } },
  },
};

test('maps legacy files to V3 entities and strips groupId', () => {
  const { entities, issues } = parseLegacyData(legacy);

  assert.equal(issues.length, 0);
  assert.equal(entities.length, 7);

  const task1 = entities.find((e) => e.entityType === 'task' && e.entityId === 'task-1');
  assert.equal(task1.lifecycle, 'active');
  assert.equal('groupId' in task1.payload, false);
  assert.equal(task1.payload.timezone, 'Asia/Shanghai');
  assert.equal(task1.payload.itemType, 'task');

  const task2 = entities.find((e) => e.entityId === 'task-2');
  assert.equal(task2.lifecycle, 'deleted');
  assert.equal(task2.deletedAt, 1700000003000);

  const journalOld = entities.find((e) => e.entityType === 'journal' && e.entityId === '2026-08-18');
  assert.equal(journalOld.payload.text, '旧版纯字符串');

  const report = entities.find((e) => e.entityType === 'insight' && e.entityId === 'report-2026-08-19');
  assert.ok(report, 'reports are imported as insight entities');
  assert.equal(report.payload.emotion, 'calm');
});

test('reports duplicate ids and skips them', () => {
  const { entities, issues } = parseLegacyData({
    events: [
      { id: 'task-1', title: 'a' },
      { id: 'task-1', title: 'b' },
    ],
  });

  assert.equal(issues.length, 1);
  assert.match(issues[0], /duplicate/);
  assert.equal(entities.length, 1);
  assert.equal(entities[0].payload.title, 'a');
});

test('flat legacy events (no payload wrapper) keep all business fields', () => {
  const { entities, issues } = parseLegacyData({
    events: [
      {
        id: 'task-flat',
        title: '速算课程',
        type: 'task',
        start: '2026-07-08T01:00:00.000Z',
        end: '2026-07-08T04:00:00.000Z',
        note: 'n',
        completed: true,
        groupId: 'g1',
        updatedAt: 1700000001000,
        deletedAt: 0,
      },
    ],
  });

  assert.equal(issues.length, 0);
  const t = entities[0];
  assert.equal(t.payload.title, '速算课程');
  assert.equal(t.payload.start, '2026-07-08T01:00:00.000Z');
  assert.equal(t.payload.end, '2026-07-08T04:00:00.000Z');
  assert.equal(t.payload.note, 'n');
  assert.equal(t.payload.completed, true);
  assert.equal(t.payload.itemType, 'task', 'itemType derived from legacy type');
  assert.equal('groupId' in t.payload, false);
  assert.equal('id' in t.payload, false);
  assert.equal('updatedAt' in t.payload, false);
  assert.equal(t.lifecycle, 'active');
});

test('imports into the database in one transaction, idempotently', () => {
  const db = openDatabase(':memory:');
  const { entities } = parseLegacyData(legacy);

  const first = importIntoDatabase(db, entities);
  assert.equal(first, 7);

  const second = importIntoDatabase(db, entities);
  assert.equal(second, 0, 'second run writes nothing');

  const row = db
    .prepare('SELECT entity_type, entity_id, lifecycle, payload_json FROM entities WHERE entity_id = ?')
    .get('task-1');
  assert.equal(row.entity_type, 'task');
  assert.equal(row.lifecycle, 'active');
  assert.equal('groupId' in JSON.parse(row.payload_json), false);

  db.close();
});
