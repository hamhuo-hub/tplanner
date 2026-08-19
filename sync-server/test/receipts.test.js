import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findReceipt, insertReceipt, receiptsForDeviceAfter, acceptedThrough } from '../src/state/receipts.js';
import { openDatabase } from '../src/state/database.js';

function sample(overrides = {}) {
  return {
    commandId: 'cmd-1',
    batchId: 'batch-1',
    deviceId: 'dev-1',
    clientSequence: 1,
    brokerSequence: 100,
    commandType: 'task.create',
    aggregateId: 'task-1',
    status: 'APPLIED',
    errorCode: null,
    snapshotVersion: 10,
    resultJson: null,
    processedAt: 1700000000000,
    ...overrides,
  };
}

test('insert then find by commandId round-trips', () => {
  const db = openDatabase(':memory:');
  insertReceipt(db, sample());

  const r = findReceipt(db, 'cmd-1');
  assert.equal(r.commandId, 'cmd-1');
  assert.equal(r.status, 'APPLIED');
  assert.equal(r.snapshotVersion, 10);
  assert.equal(r.processedAt, 1700000000000);
  db.close();
});

test('duplicate commandId insert throws (idempotency is a lookup, not an upsert)', () => {
  const db = openDatabase(':memory:');
  insertReceipt(db, sample());
  assert.throws(() => insertReceipt(db, sample({ brokerSequence: 101 })));
  db.close();
});

test('receiptsForDeviceAfter returns ordered rows after the cursor', () => {
  const db = openDatabase(':memory:');
  insertReceipt(db, sample({ commandId: 'c1', clientSequence: 1, brokerSequence: 100 }));
  insertReceipt(db, sample({ commandId: 'c2', clientSequence: 2, brokerSequence: 101, status: 'NOOP' }));
  insertReceipt(db, sample({ commandId: 'c3', clientSequence: 3, brokerSequence: 102 }));
  insertReceipt(db, sample({ commandId: 'other-dev', deviceId: 'dev-2', clientSequence: 1, brokerSequence: 103 }));

  const rows = receiptsForDeviceAfter(db, 'dev-1', 1);
  assert.deepEqual(rows.map((r) => r.clientSequence), [2, 3]);
  assert.equal(rows[0].status, 'NOOP');
  db.close();
});

test('acceptedThrough reports the highest client sequence', () => {
  const db = openDatabase(':memory:');
  assert.equal(acceptedThrough(db, 'dev-1'), 0);
  insertReceipt(db, sample({ commandId: 'c1', clientSequence: 1, brokerSequence: 200 }));
  insertReceipt(db, sample({ commandId: 'c2', clientSequence: 4, brokerSequence: 201 }));
  assert.equal(acceptedThrough(db, 'dev-1'), 4);
  db.close();
});
