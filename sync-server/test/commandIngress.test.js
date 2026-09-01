import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { buildServer } from '../src/api/app.js';
import { loadBatchValidator } from '../src/api/validation.js';

const fixturePath = (name) =>
  fileURLToPath(new URL(`../../sync-v3/protocol/v3/fixtures/${name}`, import.meta.url));

function stubPublisher() {
  const calls = [];
  return {
    calls,
    publish: async (batch) => {
      calls.push(batch);
      return {
        batchId: batch.batchId,
        brokerSequence: 4242,
        state: 'BROKER_PERSISTED',
        duplicate: false,
      };
    },
  };
}

async function loadValidBatch() {
  return JSON.parse(await readFile(fixturePath('command-batch.task-edit.valid.json'), 'utf8'));
}

test('accepts a valid batch with matching Idempotency-Key', async () => {
  const { calls, publish } = stubPublisher();
  const app = buildServer({ publisher: { publish }, validateBatch: await loadBatchValidator() });
  const batch = await loadValidBatch();

  const res = await app.inject({
    method: 'POST',
    url: '/tplanner/v3/command-batches',
    headers: { 'idempotency-key': batch.batchId },
    payload: batch,
  });

  assert.equal(res.statusCode, 202);
  assert.deepEqual(res.json(), {
    batchId: batch.batchId,
    brokerSequence: 4242,
    state: 'BROKER_PERSISTED',
    duplicate: false,
  });
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], batch);
});

test('rejects a malformed batch with SCHEMA_UNSUPPORTED', async () => {
  const { calls, publish } = stubPublisher();
  const app = buildServer({ publisher: { publish }, validateBatch: await loadBatchValidator() });
  const invalid = JSON.parse(await readFile(fixturePath('command-batch.invalid.missing-fields.json'), 'utf8'));

  const res = await app.inject({
    method: 'POST',
    url: '/tplanner/v3/command-batches',
    headers: { 'idempotency-key': invalid.batchId },
    payload: invalid,
  });

  assert.equal(res.statusCode, 400);
  assert.equal(res.json().error, 'SCHEMA_UNSUPPORTED');
  assert.equal(calls.length, 0);
});

test('transport schema accepts canonical task commands and rejects entity replacement', async () => {
  const validateBatch = await loadBatchValidator();
  const batch = await loadValidBatch();

  for (const type of [
    'task.setAppearance', 'task.setAlarm', 'task.setLocation', 'task.setExtras', 'task.setRecurrence',
  ]) {
    const candidate = structuredClone(batch);
    candidate.commands[0].type = type;
    assert.equal(validateBatch(candidate), null, `${type} must be part of the V3 protocol`);
  }

  const replaced = structuredClone(batch);
  replaced.commands[0].type = 'legacy.entityReplace';
  assert.ok(validateBatch(replaced), 'whole-entity replacement must be rejected by the transport schema');
});

test('rejects a mismatched Idempotency-Key', async () => {
  const { publish } = stubPublisher();
  const app = buildServer({ publisher: { publish }, validateBatch: await loadBatchValidator() });
  const batch = await loadValidBatch();

  const res = await app.inject({
    method: 'POST',
    url: '/tplanner/v3/command-batches',
    headers: { 'idempotency-key': '0198f2a1-3c4b-7d5e-8f90-000000000000' },
    payload: batch,
  });

  assert.equal(res.statusCode, 400);
  assert.equal(res.json().error, 'IDEMPOTENCY_KEY_MISMATCH');
});

test('rejects a request without Idempotency-Key', async () => {
  const { publish } = stubPublisher();
  const app = buildServer({ publisher: { publish }, validateBatch: await loadBatchValidator() });
  const batch = await loadValidBatch();

  const res = await app.inject({
    method: 'POST',
    url: '/tplanner/v3/command-batches',
    payload: batch,
  });

  assert.equal(res.statusCode, 400);
  assert.equal(res.json().error, 'IDEMPOTENCY_KEY_MISMATCH');
});

test('maps broker failure to 503 BROKER_UNAVAILABLE', async () => {
  const app = buildServer({
    publisher: {
      publish: async () => {
        throw new Error('nats down');
      },
    },
    validateBatch: await loadBatchValidator(),
  });
  const batch = await loadValidBatch();

  const res = await app.inject({
    method: 'POST',
    url: '/tplanner/v3/command-batches',
    headers: { 'idempotency-key': batch.batchId },
    payload: batch,
  });

  assert.equal(res.statusCode, 503);
  assert.equal(res.json().error, 'BROKER_UNAVAILABLE');
});
