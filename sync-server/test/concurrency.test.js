import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';

const SERVER_ID = 'srv-concurrency-test';

const cmd = (type, aggregateId, args, commandId, clientSequence) => ({
  commandId,
  clientSequence,
  type,
  aggregateId,
  arguments: args ?? {},
});

function entry(brokerSequence, deviceId, command, batchId = 'b1') {
  return { brokerSequence, deviceId, batchId, command };
}

function freshMaterializer() {
  const db = openDatabase(':memory:');
  return { db, m: createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID }) };
}

test('same field from two devices: broker order decides the final value', () => {
  const { m, db } = freshMaterializer();
  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: '旧' }, 'c1', 1)),
    entry(2, 'desktop', cmd('task.setTitle', 't1', { title: '桌面' }, 'd1', 1)),
    entry(3, 'phone', cmd('task.setTitle', 't1', { title: '手机' }, 'p1', 1)),
  ]);
  assert.equal(m.getState().tasks.t1.title, '手机', 'later broker sequence wins');
  db.close();
});

test('different fields from different devices both survive', () => {
  const { m, db } = freshMaterializer();
  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', {}, 'c1', 1)),
    entry(2, 'phone', cmd('task.setTitle', 't1', { title: '甲' }, 'p1', 1)),
    entry(3, 'watch', cmd('task.setCompleted', 't1', { completed: true }, 'w1', 1)),
    entry(4, 'desktop', cmd('task.setNote', 't1', { note: '乙' }, 'd1', 1)),
  ]);
  assert.equal(m.getState().tasks.t1.title, '甲');
  assert.equal(m.getState().tasks.t1.completed, true);
  assert.equal(m.getState().tasks.t1.note, '乙');
  db.close();
});

test('concurrent create with the same id: first wins, second is ID_ALREADY_EXISTS', () => {
  const { m, db } = freshMaterializer();
  const result = m.processIntegrationBatch([
    entry(1, 'phone', cmd('task.create', 't1', { title: 'a' }, 'p1', 1)),
    entry(2, 'watch', cmd('task.create', 't1', { title: 'b' }, 'w1', 1)),
  ]);
  assert.equal(result.receipts[0].status, 'APPLIED');
  assert.equal(result.receipts[1].status, 'ID_ALREADY_EXISTS');
  assert.equal(m.getState().tasks.t1.title, 'a');
  db.close();
});

test('delete vs note edit: ENTITY_DELETED regardless of which device is faster', () => {
  const { m, db } = freshMaterializer();
  m.processIntegrationBatch([
    entry(1, 'phone', cmd('task.create', 't1', {}, 'p1', 1)),
    entry(2, 'desktop', cmd('task.delete', 't1', {}, 'd1', 1)),
    entry(3, 'phone', cmd('task.setNote', 't1', { note: '迟到' }, 'p2', 2)),
  ]);
  assert.equal(m.getState().tasks.t1.lifecycle, 'deleted');
  assert.equal(m.getState().tasks.t1.note, '', 'stale edit must not resurrect or mutate');
  db.close();
});

test('checklist delete vs setCompleted: item gone means NOOP', () => {
  const { m, db } = freshMaterializer();
  const result = m.processIntegrationBatch([
    entry(1, 'phone', cmd('task.create', 't1', {}, 'p1', 1)),
    entry(2, 'phone', cmd('checklist.createItem', 't1', { checklistItemId: 'i1', title: 'x' }, 'p2', 2)),
    entry(3, 'desktop', cmd('checklist.deleteItem', 't1', { checklistItemId: 'i1' }, 'd1', 1)),
    entry(4, 'phone', cmd('checklist.setCompleted', 't1', { checklistItemId: 'i1', completed: true }, 'p3', 3)),
  ]);
  assert.equal(result.receipts[3].status, 'NOOP');
  assert.equal(m.getState().tasks.t1.checklist.length, 0);
  db.close();
});

test('list delete vs assignList: assigning to a deleted list is rejected', () => {
  const { m, db } = freshMaterializer();
  const result = m.processIntegrationBatch([
    entry(1, 'phone', cmd('list.create', 'l1', { title: '清单' }, 'p1', 1)),
    entry(2, 'phone', cmd('task.create', 't1', {}, 'p2', 2)),
    entry(3, 'desktop', cmd('list.delete', 'l1', {}, 'd1', 1)),
    entry(4, 'phone', cmd('task.assignList', 't1', { listId: 'l1' }, 'p3', 3)),
  ]);
  assert.equal(result.receipts[3].status, 'REJECTED');
  assert.equal(result.receipts[3].errorCode, 'LIST_NOT_FOUND');
  assert.equal('listId' in m.getState().tasks.t1, false);
  db.close();
});

test('device clocks are irrelevant: ordering uses broker sequence only', () => {
  const { m, db } = freshMaterializer();
  // 两个"时间"完全颠倒的设备:只有 broker 序参与裁决
  m.processIntegrationBatch([
    entry(10, 'phone', cmd('task.create', 't1', { title: '先发' }, 'p1', 1)),
    entry(20, 'desktop', cmd('task.setTitle', 't1', { title: '后发' }, 'd1', 1)),
  ]);
  assert.equal(m.getState().tasks.t1.title, '后发');
  db.close();
});
