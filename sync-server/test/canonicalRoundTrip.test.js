import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';

test('semantic commands round-trip every canonical task/list field through a snapshot', () => {
  const db = openDatabase(':memory:');
  const definitions = [
    ['list.create', 'work', { title: '工作', color: 'gold' }],
    ['task.create', 'task1', { title: '完整任务', itemType: 'task' }],
    ['task.setNote', 'task1', { note: '正文' }],
    ['task.setSchedule', 'task1', {
      schedule: { startAt: '2026-09-01T01:00:00.000Z', endAt: '2026-09-01T02:00:00.000Z' },
    }],
    ['task.assignList', 'task1', { listId: 'work' }],
    ['task.setRecurrence', 'task1', { recurrence: { frequency: 'weekly', count: 10 } }],
    ['task.setAlarm', 'task1', { enabled: true, offsetMinutes: 15 }],
    ['task.setAppearance', 'task1', { colorId: 3 }],
    ['task.setLocation', 'task1', { lat: 31.23, lng: 121.47 }],
    ['task.setExtras', 'task1', { extras: { timezone: 'Asia/Shanghai', future: { keep: true } } }],
    ['checklist.createItem', 'task1', { checklistItemId: 'check1', title: '检查', text: '不应落盘' }],
  ];
  const entries = definitions.map(([type, aggregateId, args], index) => ({
    brokerSequence: index + 1,
    batchId: 'canonical-round-trip',
    deviceId: 'phone-a',
    command: {
      commandId: `command-${index + 1}`,
      clientSequence: index + 1,
      type,
      aggregateId,
      arguments: args,
    },
  }));

  const materializer = createMaterializer({
    db,
    applyCommand,
    serverInstanceId: 'srv-round-trip',
    now: () => 1_800_000_000_000,
  });
  const { snapshot, receipts } = materializer.processIntegrationBatch(entries);

  assert.ok(receipts.every((receipt) => receipt.status === 'APPLIED'));
  const envelope = JSON.parse(gunzipSync(snapshot.compressed).toString('utf8'));
  assert.deepEqual(envelope.state.customLists.work, {
    title: '工作', color: 'gold', lifecycle: 'active', deletedAt: null,
  });
  assert.deepEqual(envelope.state.tasks.task1, {
    title: '完整任务',
    note: '正文',
    completed: false,
    itemType: 'task',
    lifecycle: 'active',
    deletedAt: null,
    schedule: { startAt: '2026-09-01T01:00:00.000Z', endAt: '2026-09-01T02:00:00.000Z' },
    listId: 'work',
    recurrence: { frequency: 'weekly', count: 10 },
    alarm: { enabled: true, offsetMinutes: 15 },
    colorId: 3,
    location: { lat: 31.23, lng: 121.47 },
    extras: { timezone: 'Asia/Shanghai', future: { keep: true } },
    checklist: [{ id: 'check1', title: '检查', completed: false }],
  });
  db.close();
});
