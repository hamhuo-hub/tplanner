import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createIntegrationBatchReader, expandMessage, LIMITS } from '../src/materializer/main.js';

function message(seq, commandId = `c-${seq}`) {
  return {
    seq,
    data: Buffer.from(JSON.stringify({
      batchId: `b-${seq}`,
      deviceId: 'dev-1',
      commands: [{ commandId, clientSequence: seq, type: 'task.create', aggregateId: `t-${seq}`, arguments: {} }],
    })),
    ack() {},
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

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

test('batch reader ends an isolated command after the local quiet window, not the 1s pull expiry', async () => {
  const pending = deferred();
  const afterPending = deferred();
  const expires = [];
  let calls = 0;
  const consumer = {
    next: ({ expires: value }) => {
      expires.push(value);
      calls += 1;
      if (calls === 1) return Promise.resolve(message(1));
      if (calls === 2) return pending.promise;
      return afterPending.promise;
    },
  };
  const reader = createIntegrationBatchReader(consumer, {
    limits: LIMITS,
  });

  const started = Date.now();
  const batch = await reader.nextBatch();
  const elapsed = Date.now() - started;

  assert.equal(batch.messages.length, 1);
  assert.equal(LIMITS.quietMs, 100);
  assert.equal(LIMITS.forcedMs, 5000);
  assert.ok(elapsed >= 75, `local quiet boundary returned too early (${elapsed}ms)`);
  assert.ok(elapsed < 500, `local quiet boundary took ${elapsed}ms`);
  assert.deepEqual(expires, [60_000, 1_000]);

  // The in-flight pull was not abandoned: its eventual message starts the next batch.
  const nextBatch = reader.nextBatch();
  pending.resolve(message(2));
  const second = await nextBatch;
  assert.equal(second.messages[0].seq, 2);
});

test('batch reader coalesces messages that arrive inside the quiet window', async () => {
  const never = deferred();
  const values = [message(1), message(2)];
  const consumer = {
    next: () => values.length ? Promise.resolve(values.shift()) : never.promise,
  };
  const reader = createIntegrationBatchReader(consumer, {
    limits: { quietMs: 10, forcedMs: 5000, maxCommands: 100, maxBytes: 1024 * 1024 },
  });

  const batch = await reader.nextBatch();
  assert.deepEqual(batch.messages.map((m) => m.seq), [1, 2]);
  assert.deepEqual(batch.entries.map((e) => e.brokerSequence), [1_000_000, 2_000_000]);
});
