import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/state/database.js';
import { createOutboxDriver } from '../src/materializer/outbox.js';

function seedRow(
  db,
  { id = 'p-1', type = 'snapshot.ready', dedupeKey = 'snapshot.ready:v1', payload = { snapshotVersion: 1 } } = {},
) {
  db.prepare(`
    INSERT INTO publication_outbox
      (publication_id, publication_type, dedupe_key, payload_json, state, attempt_count, next_attempt_at, created_at)
    VALUES (?, ?, ?, ?, 'pending', 0, 0, ?)
  `).run(id, type, dedupeKey, JSON.stringify(payload), 1_000_000);
}

test('flush publishes pending rows and marks them published', async () => {
  const db = openDatabase(':memory:');
  seedRow(db);
  const published = [];
  const driver = createOutboxDriver(db, {
    publish: async (type, payload, dedupeKey) => published.push({ type, payload, dedupeKey }),
    now: () => 2_000_000,
  });

  assert.deepEqual(await driver.flush(), ['p-1']);
  assert.equal(published.length, 1);
  assert.equal(published[0].type, 'snapshot.ready');
  assert.equal(published[0].payload.snapshotVersion, 1);
  assert.equal(published[0].dedupeKey, 'snapshot.ready:v1');

  const row = db.prepare("SELECT state, published_at FROM publication_outbox WHERE publication_id = 'p-1'").get();
  assert.equal(row.state, 'published');
  assert.equal(row.published_at, 2_000_000);
  assert.equal(driver.pendingCount(), 0);
  db.close();
});

test('failed publish stays pending with backoff; next flush retries', async () => {
  const db = openDatabase(':memory:');
  seedRow(db);
  let calls = 0;
  let now = 2_000_000;
  const driver = createOutboxDriver(db, {
    publish: async () => {
      calls += 1;
      throw new Error('nats down');
    },
    now: () => now,
  });

  await assert.rejects(driver.flush());
  assert.equal(calls, 1);

  const row = db
    .prepare("SELECT state, attempt_count, next_attempt_at FROM publication_outbox WHERE publication_id = 'p-1'")
    .get();
  assert.equal(row.state, 'pending');
  assert.equal(row.attempt_count, 1);
  assert.equal(row.next_attempt_at, 2_001_000);

  // 未到期跳过;到期后重试成功
  assert.deepEqual(await driver.flush(), []);
  now = 2_002_000;
  const recovered = createOutboxDriver(db, {
    publish: async () => {
      calls += 1;
    },
    now: () => now,
  });
  assert.deepEqual(await recovered.flush(), ['p-1']);
  assert.equal(calls, 2);
  assert.equal(recovered.pendingCount(), 0);
  db.close();
});

test('pending rows not yet due are skipped', async () => {
  const db = openDatabase(':memory:');
  seedRow(db);
  db.prepare('UPDATE publication_outbox SET next_attempt_at = ? WHERE publication_id = ?').run(9_999_999, 'p-1');

  const published = [];
  const driver = createOutboxDriver(db, {
    publish: async (t, p, d) => published.push({ t, p, d }),
    now: () => 2_000_000,
  });

  assert.deepEqual(await driver.flush(), []);
  assert.equal(published.length, 0);
  assert.equal(driver.pendingCount(), 1);
  db.close();
});
