import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyCommand, emptyState } from '../src/materializer/reducer.js';

const cmd = (type, aggregateId, args, seq = 1) => ({ type, aggregateId, arguments: args ?? {} });

function run(state, type, aggregateId, args, seq) {
  return applyCommand(state, cmd(type, aggregateId, args, seq), seq);
}

test('list lifecycle: create, rename, color, delete with task reassignment', () => {
  let state = emptyState();
  ({ state } = run(state, 'list.create', 'list-1', { title: '工作', color: 'gold' }, 1));
  ({ state } = run(state, 'task.create', 't1', {}, 2));
  ({ state } = run(state, 'task.assignList', 't1', { listId: 'list-1' }, 3));
  assert.equal(state.tasks.t1.listId, 'list-1');

  const r = run(state, 'list.rename', 'list-1', { title: '工作2' }, 4);
  assert.equal(r.state.customLists['list-1'].title, '工作2');

  ({ state } = run(state, 'list.delete', 'list-1', {}, 5));
  assert.equal(state.customLists['list-1'].lifecycle, 'deleted');
  assert.equal(state.tasks.t1.listId, null, 'deleted list must canonically unassign its tasks');

  const edit = run(state, 'list.rename', 'list-1', { title: 'x' }, 6);
  assert.equal(edit.receipt.status, 'ENTITY_DELETED');
});

test('assignList rejects missing or deleted lists', () => {
  let state = emptyState();
  ({ state } = run(state, 'task.create', 't1', {}, 1));
  const missing = run(state, 'task.assignList', 't1', { listId: 'nope' }, 2);
  assert.equal(missing.receipt.status, 'REJECTED');
  assert.equal(missing.receipt.errorCode, 'LIST_NOT_FOUND');

  ({ state } = run(state, 'list.create', 'list-1', {}, 3));
  ({ state } = run(state, 'list.delete', 'list-1', {}, 4));
  const deleted = run(state, 'task.assignList', 't1', { listId: 'list-1' }, 5);
  assert.equal(deleted.receipt.status, 'REJECTED');
});

test('journal setText creates or updates, delete then edit is ENTITY_DELETED', () => {
  let state = emptyState();
  ({ state } = run(state, 'journal.setText', '2026-08-19', { text: '早上好' }, 1));
  assert.equal(state.journals['2026-08-19'].text, '早上好');

  const same = run(state, 'journal.setText', '2026-08-19', { text: '早上好' }, 2);
  assert.equal(same.receipt.status, 'NOOP');

  ({ state } = run(state, 'journal.delete', '2026-08-19', {}, 3));
  assert.equal(state.journals['2026-08-19'].lifecycle, 'deleted');
  const edit = run(state, 'journal.setText', '2026-08-19', { text: '复活?' }, 4);
  assert.equal(edit.receipt.status, 'ENTITY_DELETED');
});

test('goal patch merges fields and cannot touch lifecycle', () => {
  let state = emptyState();
  ({ state } = run(state, 'goal.create', 'goal-1', { title: '读书' }, 1));

  ({ state } = run(state, 'goal.patch', 'goal-1', { patch: { progress: 42 } }, 2));
  assert.equal(state.goals['goal-1'].title, '读书');
  assert.equal(state.goals['goal-1'].progress, 42);
  assert.equal(state.goals['goal-1'].lifecycle, 'active');

  ({ state } = run(state, 'goal.patch', 'goal-1', { patch: { lifecycle: 'deleted', deletedAt: 99, title: 'x' } }, 3));
  assert.equal(state.goals['goal-1'].lifecycle, 'active');
  assert.equal(state.goals['goal-1'].deletedAt, null);
  assert.equal(state.goals['goal-1'].title, 'x');

  ({ state } = run(state, 'goal.delete', 'goal-1', {}, 4));
  const patchDeleted = run(state, 'goal.patch', 'goal-1', { patch: { title: '复活?' } }, 5);
  assert.equal(patchDeleted.receipt.status, 'ENTITY_DELETED');
});

test('insight upsert replaces payload; upsert on deleted is ENTITY_DELETED', () => {
  let state = emptyState();
  ({ state } = run(state, 'insight.upsert', 'ins-1', { payload: { text: 'a', n: 1 } }, 1));
  assert.equal(state.insights['ins-1'].text, 'a');
  assert.equal(state.insights['ins-1'].lifecycle, 'active');

  ({ state } = run(state, 'insight.upsert', 'ins-1', { payload: { text: 'b' } }, 2));
  assert.equal(state.insights['ins-1'].text, 'b');
  assert.equal('n' in state.insights['ins-1'], false, 'upsert replaces the whole payload');

  ({ state } = run(state, 'insight.delete', 'ins-1', {}, 3));
  const resurrect = run(state, 'insight.upsert', 'ins-1', { payload: { text: 'c' } }, 4);
  assert.equal(resurrect.receipt.status, 'ENTITY_DELETED');
});

test('unknown and malformed commands are rejected, not thrown', () => {
  let state = emptyState();
  const unknown = run(state, 'goal.toggle', 'g1', {}, 1);
  assert.equal(unknown.receipt.status, 'REJECTED');
  assert.equal(unknown.receipt.errorCode, 'SCHEMA_UNSUPPORTED');

  const badPatch = run(state, 'goal.patch', 'g1', { patch: 'not-an-object' }, 2);
  assert.equal(badPatch.receipt.status, 'REJECTED');
});
