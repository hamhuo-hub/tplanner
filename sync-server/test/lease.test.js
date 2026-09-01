import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/state/database.js';
import { createLease } from '../src/materializer/lease.js';

const leaseRow = (db) =>
  db.prepare('SELECT owner_id, lease_expires_at FROM state_builder_lease WHERE singleton_id = 1').get();

test('first acquirer takes the lease', () => {
  const db = openDatabase(':memory:');
  const lease = createLease(db, { ttlMs: 30_000 });

  const r = lease.acquire('builder-a', 1_000_000);
  assert.deepEqual(r, { acquired: true, ownerId: 'builder-a' });

  const row = leaseRow(db);
  assert.equal(row.owner_id, 'builder-a');
  assert.equal(row.lease_expires_at, 1_030_000);
  db.close();
});

test('second instance cannot steal an unexpired lease', () => {
  const db = openDatabase(':memory:');
  const lease = createLease(db, { ttlMs: 30_000 });

  lease.acquire('builder-a', 1_000_000);
  const r = lease.acquire('builder-b', 1_010_000); // 未过期

  assert.equal(r.acquired, false);
  assert.equal(r.currentOwner, 'builder-a');
  assert.equal(leaseRow(db).owner_id, 'builder-a'); // 未被抢占
  db.close();
});

test('expired lease can be taken over', () => {
  const db = openDatabase(':memory:');
  const lease = createLease(db, { ttlMs: 30_000 });

  lease.acquire('builder-a', 1_000_000);
  const r = lease.acquire('builder-b', 1_030_001); // 已过期

  assert.equal(r.acquired, true);
  assert.equal(leaseRow(db).owner_id, 'builder-b');
  db.close();
});

test('owner renews its own lease; takeover invalidates the old owner', () => {
  const db = openDatabase(':memory:');
  const lease = createLease(db, { ttlMs: 30_000 });

  lease.acquire('builder-a', 1_000_000);
  assert.equal(lease.renewLease('builder-a', 1_010_000), true);
  assert.equal(lease.renewLease('builder-b', 1_010_000), false); // 非持有者不能续
  assert.equal(leaseRow(db).lease_expires_at, 1_040_000);

  // 过期接管后,旧持有者续期失败
  lease.acquire('builder-b', 1_040_001);
  assert.equal(lease.renewLease('builder-a', 1_050_000), false);
  assert.equal(leaseRow(db).owner_id, 'builder-b');
  db.close();
});

test('owner releases its lease on graceful shutdown without releasing another owner', () => {
  const db = openDatabase(':memory:');
  const lease = createLease(db, { ttlMs: 30_000 });
  lease.acquire('builder-a', 1_000_000);

  assert.equal(lease.releaseLease('builder-b'), false);
  assert.equal(leaseRow(db).lease_expires_at, 1_030_000);
  assert.equal(lease.releaseLease('builder-a'), true);
  assert.equal(leaseRow(db).lease_expires_at, 0);
  assert.equal(lease.acquire('builder-b', 1_000_001).acquired, true);
  db.close();
});
