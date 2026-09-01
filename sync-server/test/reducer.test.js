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

test('task.create always produces the complete canonical entity shape', () => {
  const { state } = run('task.create', 't1', { title: '新任务', itemType: 'note' });
  assert.deepEqual(state.tasks.t1, {
    title: '新任务',
    note: '',
    completed: false,
    itemType: 'note',
    schedule: null,
    recurrence: null,
    alarm: { enabled: false, offsetMinutes: 0 },
    colorId: 0,
    location: { lat: null, lng: null },
    extras: {},
    listId: null,
    checklist: [],
    lifecycle: 'active',
    deletedAt: null,
  });
});

test('create then edit different fields keeps both edits', () => {
  let { state } = run('task.create', 't1', { title: '旧' });
  ({ state } = applyCommand(state, cmd('task.setTitle', 't1', { title: '新' }, 2), 2));
  ({ state } = applyCommand(state, cmd('task.setCompleted', 't1', { completed: true }, 3), 3));
  assert.equal(state.tasks.t1.title, '新');
  assert.equal(state.tasks.t1.completed, true);
});

test('canonical task field commands preserve unrelated data', () => {
  let { state } = run('task.create', 't1', { title: '完整任务' });
  ({ state } = applyCommand(state, cmd('task.setExtras', 't1', {
    extras: { futureField: { nested: true }, recurrenceType: 'weekly' },
  }, 2), 2));
  ({ state } = applyCommand(state, cmd('task.setAlarm', 't1', {
    enabled: true,
    offsetMinutes: 15,
    futureSound: { name: 'chime' },
  }, 3), 3));
  ({ state } = applyCommand(state, cmd('task.setAppearance', 't1', { colorId: 3 }, 4), 4));
  ({ state } = applyCommand(state, cmd('task.setLocation', 't1', {
    lat: 31.23,
    lng: 121.47,
    futureAccuracy: 3,
  }, 5), 5));
  ({ state } = applyCommand(state, cmd('task.setRecurrence', 't1', {
    recurrence: { frequency: 'weekly', count: 10, futureRule: 'keep-me' },
  }, 6), 6));
  ({ state } = applyCommand(state, cmd('task.setAlarm', 't1', {
    enabled: false,
    offsetMinutes: 5,
  }, 7), 7));
  ({ state } = applyCommand(state, cmd('task.setLocation', 't1', {
    lat: null,
    lng: null,
  }, 8), 8));
  ({ state } = applyCommand(state, cmd('task.setTitle', 't1', { title: '只改标题' }, 7), 7));

  assert.deepEqual(state.tasks.t1.extras, {
    futureField: { nested: true },
    recurrenceType: 'weekly',
  });
  assert.deepEqual(state.tasks.t1.alarm, {
    enabled: false,
    offsetMinutes: 5,
    futureSound: { name: 'chime' },
  });
  assert.equal(state.tasks.t1.colorId, 3);
  assert.deepEqual(state.tasks.t1.location, { lat: null, lng: null, futureAccuracy: 3 });
  assert.deepEqual(state.tasks.t1.recurrence, {
    frequency: 'weekly',
    count: 10,
    futureRule: 'keep-me',
  });
});

test('bootstrap guards never overwrite existing central values', () => {
  let state = emptyState();
  let sequence = 0;
  const apply = (type, aggregateId, args = {}) => {
    const result = applyCommand(state, cmd(type, aggregateId, args, ++sequence), sequence);
    state = result.state;
    return result.receipt;
  };

  apply('task.create', 't1', { title: 'central' });
  apply('list.create', 'central-list', { title: 'central' });
  apply('list.create', 'local-list', { title: 'local' });
  apply('task.setSchedule', 't1', {
    schedule: { startAt: '2026-09-01T01:00:00.000Z', endAt: null },
  });
  apply('task.setRecurrence', 't1', {
    recurrence: { frequency: 'weekly', count: 2, futureRule: 'central' },
  });
  apply('task.setAlarm', 't1', {
    enabled: true, offsetMinutes: 30, futureSound: 'central',
  });
  apply('task.setLocation', 't1', {
    lat: 31.23, lng: 121.47, futureAccuracy: 5,
  });
  apply('task.setExtras', 't1', {
    extras: { shared: 'central', remoteOnly: true },
  });
  apply('task.assignList', 't1', { listId: 'central-list' });
  apply('journal.setText', '2026-09-01', { text: 'central journal' });

  assert.equal(apply('task.setSchedule', 't1', {
    schedule: { startAt: '2026-10-01T01:00:00.000Z', endAt: null },
    ifMissing: true,
  }).status, 'NOOP');
  assert.equal(apply('task.setRecurrence', 't1', {
    recurrence: { frequency: 'daily', count: 1 },
    ifMissing: true,
  }).status, 'NOOP');
  assert.equal(apply('task.setAlarm', 't1', {
    enabled: false, offsetMinutes: 0, ifMissing: true,
  }).status, 'NOOP');
  assert.equal(apply('task.setLocation', 't1', {
    lat: 0, lng: 0, ifMissing: true,
  }).status, 'NOOP');
  assert.equal(apply('task.assignList', 't1', {
    listId: 'local-list', ifUnassigned: true,
  }).status, 'NOOP');
  assert.equal(apply('journal.setText', '2026-09-01', {
    text: 'local journal', ifMissing: true,
  }).status, 'NOOP');
  assert.equal(apply('task.setExtras', 't1', {
    extras: { shared: 'local', localOnly: true }, mergeMissing: true,
  }).status, 'APPLIED');

  assert.deepEqual(state.tasks.t1.schedule, {
    startAt: '2026-09-01T01:00:00.000Z', endAt: null,
  });
  assert.deepEqual(state.tasks.t1.recurrence, {
    frequency: 'weekly', count: 2, futureRule: 'central',
  });
  assert.deepEqual(state.tasks.t1.alarm, {
    enabled: true, offsetMinutes: 30, futureSound: 'central',
  });
  assert.deepEqual(state.tasks.t1.location, {
    lat: 31.23, lng: 121.47, futureAccuracy: 5,
  });
  assert.deepEqual(state.tasks.t1.extras, {
    localOnly: true, shared: 'central', remoteOnly: true,
  });
  assert.equal(state.tasks.t1.listId, 'central-list');
  assert.equal(state.journals['2026-09-01'].text, 'central journal');
});

test('bootstrap guards fill missing values without entering canonical state', () => {
  let state = emptyState();
  let sequence = 0;
  const apply = (type, aggregateId, args = {}) => {
    ({ state } = applyCommand(state, cmd(type, aggregateId, args, ++sequence), sequence));
  };

  apply('task.create', 't1', { title: 'local-only fields' });
  apply('list.create', 'local-list', { title: 'local' });
  apply('task.setSchedule', 't1', {
    schedule: { startAt: '2026-09-02T01:00:00.000Z', endAt: null },
    ifMissing: true,
  });
  apply('task.setRecurrence', 't1', {
    recurrence: { frequency: 'daily', count: 1 }, ifMissing: true,
  });
  apply('task.setAlarm', 't1', {
    enabled: true, offsetMinutes: 10, futureSound: 'local', ifMissing: true,
  });
  apply('task.setLocation', 't1', {
    lat: 30.1, lng: 120.2, futureAccuracy: 7, ifMissing: true,
  });
  apply('task.setExtras', 't1', {
    extras: { localOnly: true }, mergeMissing: true,
  });
  apply('task.assignList', 't1', { listId: 'local-list', ifUnassigned: true });
  apply('journal.setText', '2026-09-02', { text: 'local journal', ifMissing: true });

  assert.deepEqual(state.tasks.t1.schedule, {
    startAt: '2026-09-02T01:00:00.000Z', endAt: null,
  });
  assert.deepEqual(state.tasks.t1.recurrence, { frequency: 'daily', count: 1 });
  assert.deepEqual(state.tasks.t1.alarm, {
    enabled: true, offsetMinutes: 10, futureSound: 'local',
  });
  assert.deepEqual(state.tasks.t1.location, {
    lat: 30.1, lng: 120.2, futureAccuracy: 7,
  });
  assert.deepEqual(state.tasks.t1.extras, { localOnly: true });
  assert.equal(state.tasks.t1.listId, 'local-list');
  assert.deepEqual(state.journals['2026-09-02'], {
    text: 'local journal', lifecycle: 'active', deletedAt: null,
  });
  assert.doesNotMatch(JSON.stringify(state), /ifMissing|mergeMissing|ifUnassigned/);
});

test('canonical task field commands reject malformed payloads without mutating state', () => {
  const { state } = run('task.create', 't1', {});
  for (const command of [
    cmd('task.setAlarm', 't1', { enabled: 'yes' }),
    cmd('task.setLocation', 't1', { lat: '1', lng: null }),
    cmd('task.setExtras', 't1', { extras: ['not-an-object'] }),
    cmd('task.setRecurrence', 't1', { recurrence: 'weekly' }),
    cmd('task.setSchedule', 't1', { schedule: { startAt: '2026-09-01T00:00:00.000Z' } }),
  ]) {
    const result = applyCommand(state, command, 2);
    assert.equal(result.receipt.status, 'REJECTED');
    assert.equal(result.state, state);
  }
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

test('checklist commands accept transitional text but only persist canonical title', () => {
  let { state } = run('task.create', 't1', {});
  ({ state } = applyCommand(state, cmd('checklist.createItem', 't1', {
    checklistItemId: 'old-phone',
    text: '不能丢失',
  }, 2), 2));
  assert.deepEqual(state.tasks.t1.checklist[0], {
    id: 'old-phone',
    title: '不能丢失',
    completed: false,
  });

  // Simulate an old persisted item reaching the reducer before the one-shot migration.
  state = {
    ...state,
    tasks: {
      ...state.tasks,
      t1: { ...state.tasks.t1, checklist: [{ id: 'legacy', text: '旧文本', completed: false }] },
    },
  };
  ({ state } = applyCommand(state, cmd('checklist.setCompleted', 't1', {
    checklistItemId: 'legacy',
    completed: true,
  }, 3), 3));
  assert.deepEqual(state.tasks.t1.checklist[0], {
    id: 'legacy',
    title: '旧文本',
    completed: true,
  });
  assert.equal('text' in state.tasks.t1.checklist[0], false);
});

test('checklist edit on deleted task is ENTITY_DELETED', () => {
  let { state } = run('task.create', 't1', {});
  ({ state } = applyCommand(state, cmd('task.delete', 't1', {}, 2), 2));
  const r = applyCommand(state, cmd('checklist.createItem', 't1', { checklistItemId: 'x' }, 3), 3);
  assert.equal(r.receipt.status, 'ENTITY_DELETED');
});
