import { test } from 'node:test';
import assert from 'node:assert/strict';
import { expandMessage } from '../src/materializer/main.js';

test('expandMessage derives ordered per-command broker sequences from the MQ seq', () => {
  const batch = {
    batchId: 'b-1',
    deviceId: 'dev-1',
    commands: [
      { commandId: 'c1', clientSequence: 1, type: 'task.create', aggregateId: 't1', arguments: {} },
      { commandId: 'c2', clientSequence: 2, type: 'task.setTitle', aggregateId: 't1', arguments: { title: 'x' } },
    ],
  };
  const msg = { seq: 501, data: Buffer.from(JSON.stringify(batch), 'utf8') };

  const entries = expandMessage(msg);

  assert.equal(entries.length, 2);
  assert.equal(entries[0].brokerSequence, 501 * 1_000_000);
  assert.equal(entries[1].brokerSequence, 501 * 1_000_000 + 1);
  assert.equal(entries[0].deviceId, 'dev-1');
  assert.equal(entries[0].batchId, 'b-1');
  assert.deepEqual(entries[1].command, batch.commands[1]);
});

test('expandMessage tolerates batches without commands', () => {
  const msg = { seq: 7, data: Buffer.from(JSON.stringify({ batchId: 'empty', deviceId: 'dev-1' }), 'utf8') };
  assert.deepEqual(expandMessage(msg), []);
});
