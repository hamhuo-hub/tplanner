import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { applyCommand, emptyState } from '../src/materializer/reducer.js';

const fixture = (name) =>
  fileURLToPath(new URL(`../../sync-v3/protocol/v3/fixtures/reducer/${name}`, import.meta.url));

// ── 契约测试:重放 sequence-01,状态与回执必须与 fixtures 逐键一致 ──────────
test('replays sequence-01 to the exact expected state and receipts', async () => {
  const input = JSON.parse(await readFile(fixture('sequence-01.input.json'), 'utf8'));
  const expectedState = JSON.parse(await readFile(fixture('sequence-01.expected-state.json'), 'utf8'));
  const expectedReceipts = JSON.parse(await readFile(fixture('sequence-01.expected-receipts.json'), 'utf8'));

  let state = emptyState();
  const receipts = [];
  for (const entry of input.commands) {
    const result = applyCommand(state, entry.command, entry.brokerSequence);
    state = result.state;
    receipts.push({ brokerSequence: entry.brokerSequence, status: result.receipt.status });
  }

  assert.deepEqual(state, expectedState);
  assert.deepEqual({ receipts }, expectedReceipts);
});

// ── 规则级单元测试 ──────────────────────────────────────────────────────────
const cmd = (type, aggregateId, arguments_, brokerSequence = 1) => ({
  type,
  aggregateId,
  arguments: arguments_ ?? {},
  ...(brokerSequence != null ? { brokerSequence } : {}),
});

function run(type, aggregateId, args, seq = 1) {
  return applyCommand(emptyState(), cmd(type, aggregateId, args, seq), seq);
}

test('create then edit different fields keeps both edits', () => {
  let { state } = run('task.create', 't1', { title: '旧' });
  ({ state } = applyCommand(state, cmd('task.setTitle', 't1', { title: '新' }, 2), 2));
  ({ state } = applyCommand(state, cmd('task.setCompleted', 't1', { completed: true }, 3), 3));
  assert.equal(state.tasks.t1.title, '新');
  assert.equal(state.tasks.t1.completed, true);
});

test('same value writes produce NOOP and no state change', () => {
  const { state: s1 } = run('task.create', 't1', { title: 'x' });
  const r = applyCommand(s1, cmd('task.setTitle', 't1', { title: 'x' }, 2), 2);
  assert.equal(r.receipt.status, 'NOOP');
  assert.equal(r.state, s1);
});

test('edit after delete is ENTITY_DELETED; only restore revives', () => {
  let { state } = run('task.create', 't1', {});
  ({ state } = applyCommand(state, cmd('task.delete', 't1', {}, 2), 2));
  assert.equal(state.tasks.t1.lifecycle, 'deleted');
  assert.equal(state.tasks.t1.deletedAt, 2);

  const afterEdit = applyCommand(state, cmd('task.setNote', 't1', { note: 'nope' }, 3), 3);
  assert.equal(afterEdit.receipt.status, 'ENTITY_DELETED');
  assert.equal(afterEdit.state, state, 'deleted edit must not mutate state');

  const afterRestore = applyCommand(state, cmd('task.restore', 't1', {}, 4), 4);
  assert.equal(afterRestore.receipt.status, 'APPLIED');
  assert.equal(afterRestore.state.tasks.t1.lifecycle, 'active');
  assert.equal(afterRestore.state.tasks.t1.deletedAt, null);
});

test('double delete is NOOP with NOOP_ALREADY_DELETED', () => {
  let { state } = run('task.create', 't1', {});
  ({ state } = applyCommand(state, cmd('task.delete', 't1', {}, 2), 2));
  const r = applyCommand(state, cmd('task.delete', 't1', {}, 3), 3);
  assert.equal(r.receipt.status, 'NOOP');
  assert.equal(r.receipt.errorCode, 'NOOP_ALREADY_DELETED');
});

test('duplicate create is ID_ALREADY_EXISTS', () => {
  const { state } = run('task.create', 't1', { title: 'a' });
  const r = applyCommand(state, cmd('task.create', 't1', { title: 'b' }, 2), 2);
  assert.equal(r.receipt.status, 'ID_ALREADY_EXISTS');
  assert.equal(state.tasks.t1.title, 'a');
});

test('unknown command type is rejected as SCHEMA_UNSUPPORTED', () => {
  const r = applyCommand(emptyState(), { type: 'task.toggleCompleted', aggregateId: 't1' }, 1);
  assert.equal(r.receipt.status, 'REJECTED');
  assert.equal(r.receipt.errorCode, 'SCHEMA_UNSUPPORTED');
});

test('moveInTimeline shifts schedule and NOOPs without one', () => {
  let { state } = run('task.create', 't1', {});
  const noSchedule = applyCommand(state, cmd('task.moveInTimeline', 't1', { offsetMinutes: 30 }, 2), 2);
  assert.equal(noSchedule.receipt.status, 'NOOP');

  const startAt = '2026-08-20T01:00:00.000Z';
  ({ state } = applyCommand(state, cmd('task.setSchedule', 't1', { schedule: { startAt, endAt: null } }, 3), 3));
  const moved = applyCommand(state, cmd('task.moveInTimeline', 't1', { offsetMinutes: 30 }, 4), 4);
  assert.equal(moved.state.tasks.t1.schedule.startAt, '2026-08-20T01:30:00.000Z');
});

test('checklist lifecycle: create, complete, reorder by token, delete', () => {
  let { state } = run('task.create', 't1', {});
  for (const [itemId, seq] of [['a', 2], ['b', 3], ['c', 4]]) {
    ({ state } = applyCommand(state, cmd('checklist.createItem', 't1', { checklistItemId: itemId }, seq), seq));
  }
  assert.deepEqual(state.tasks.t1.checklist.map((i) => i.id), ['a', 'b', 'c']);

  ({ state } = applyCommand(state, cmd('checklist.setCompleted', 't1', { checklistItemId: 'a', completed: true }, 5), 5));
  assert.equal(state.tasks.t1.checklist[0].completed, true);

  // 把 c 移到 a 之前
  ({ state } = applyCommand(state, cmd('checklist.reorderItem', 't1', { checklistItemId: 'c', beforeItemId: 'a' }, 6), 6));
  assert.deepEqual(state.tasks.t1.checklist.map((i) => i.id), ['c', 'a', 'b']);

  ({ state } = applyCommand(state, cmd('checklist.deleteItem', 't1', { checklistItemId: 'b' }, 7), 7));
  assert.deepEqual(state.tasks.t1.checklist.map((i) => i.id), ['c', 'a']);

  // 对已删 item 的编辑是 NOOP
  const gone = applyCommand(state, cmd('checklist.setCompleted', 't1', { checklistItemId: 'b', completed: true }, 8), 8);
  assert.equal(gone.receipt.status, 'NOOP');
});

test('checklist edit on deleted task is ENTITY_DELETED', () => {
  let { state } = run('task.create', 't1', {});
  ({ state } = applyCommand(state, cmd('task.delete', 't1', {}, 2), 2));
  const r = applyCommand(state, cmd('checklist.createItem', 't1', { checklistItemId: 'x' }, 3), 3);
  assert.equal(r.receipt.status, 'ENTITY_DELETED');
});
