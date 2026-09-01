import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';
import { createMonitoring } from '../src/api/monitoring.js';
import { buildServer } from '../src/api/app.js';
import { createStore } from '../src/api/store.js';

const fakeJsm = (lastSeq) => ({
  streams: {
    info: async () => {
      if (lastSeq === null) throw new Error('nats down');
      return { state: { last_seq: lastSeq } };
    },
  },
});

function buildApp(db, { lastSeq = 0, now = () => Date.now() } = {}) {
  const store = createStore(db, { serverInstanceId: 'srv-mon-test' });
  const health = createMonitoring({ db, jsm: fakeJsm(lastSeq), serverInstanceId: 'srv-mon-test', now });
  const app = buildServer({
    publisher: { publish: async () => ({}) },
    validateBatch: () => null,
    store,
    health,
  });
  return { app, health };
}

function seedCommands(db) {
  const m = createMaterializer({ db, applyCommand, serverInstanceId: 'srv-mon-test' });
  return m.processIntegrationBatch([
    {
      brokerSequence: 501 * 1_000_000,
      deviceId: 'dev-1',
      batchId: 'b-1',
      command: {
        commandId: 'c-1',
        clientSequence: 1,
        type: 'task.create',
        aggregateId: 't-1',
        arguments: { title: '任务' },
      },
    },
    {
      brokerSequence: 502 * 1_000_000,
      deviceId: 'dev-1',
      batchId: 'b-2',
      command: {
        commandId: 'c-2',
        clientSequence: 2,
        type: 'task.setCompleted',
        aggregateId: 't-1',
        arguments: { completed: true },
      },
    },
  ]);
}

test('health/live always reports alive', async () => {
  const db = openDatabase(':memory:');
  const { app } = buildApp(db);
  const res = await app.inject({ method: 'GET', url: '/health/live' });
  assert.equal(res.statusCode, 200);
  assert.equal(res.json().status, 'alive');
  db.close();
});

test('/health is an alias for V3 readiness', async () => {
  const db = openDatabase(':memory:');
  const { app } = buildApp(db, { now: () => 1_000_000 });

  const before = await app.inject({ method: 'GET', url: '/health' });
  assert.equal(before.statusCode, 503);
  assert.equal(before.json().checks.latestSnapshot, false);

  seedCommands(db);
  db.prepare('INSERT INTO state_builder_lease (singleton_id, owner_id, lease_expires_at) VALUES (1, ?, ?)').run(
    'builder-a',
    1_030_000,
  );
  const after = await app.inject({ method: 'GET', url: '/health' });
  assert.equal(after.statusCode, 200);
  assert.equal(after.json().ok, true);
  db.close();
});

test('status reports latest version, broker sequence, materialized through, and queue lag', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  const { app } = buildApp(db, { lastSeq: 520 });

  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/status' });
  assert.equal(res.statusCode, 200);
  const body = res.json();
  assert.equal(body.softwareVersion, '8.0.0');
  assert.equal(body.serverInstanceId, 'srv-mon-test');
  assert.equal(body.latestSnapshotVersion, 1);
  assert.equal(body.brokerLastSequence, 520);
  assert.equal(body.materializedThroughSequence, 502);
  assert.equal(body.queueLag, 18);
  assert.equal(body.brokerOk, true);
  db.close();
});

test('status reports brokerOk false when NATS is unreachable', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  const { app } = buildApp(db, { lastSeq: null });

  const body = (await app.inject({ method: 'GET', url: '/tplanner/v3/status' })).json();
  assert.equal(body.brokerOk, false);
  assert.equal(body.brokerLastSequence, 0);
  assert.equal(body.materializedThroughSequence, 502); // 数据库侧不受影响
  db.close();
});

test('readiness is 503 until snapshot exists and lease is live; 200 once all checks pass', async () => {
  const db = openDatabase(':memory:');

  // 什么都没有:snapshot 缺失 + lease 缺失
  const { app } = buildApp(db, { now: () => 1_000_000 });
  const before = await app.inject({ method: 'GET', url: '/health/ready' });
  assert.equal(before.statusCode, 503);
  assert.equal(before.json().checks.latestSnapshot, false);
  assert.equal(before.json().checks.builderLease, false);
  assert.equal(before.json().checks.broker, true); // 假 jsm 在线
  assert.equal(before.json().checks.database, true);

  // 有快照 + 租约期内 → 就绪
  seedCommands(db);
  db.prepare('INSERT INTO state_builder_lease (singleton_id, owner_id, lease_expires_at) VALUES (1, ?, ?)').run(
    'builder-a',
    1_030_000,
  );
  const after = await app.inject({ method: 'GET', url: '/health/ready' });
  assert.equal(after.statusCode, 200);
  assert.equal(after.json().ok, true);
  db.close();
});

test('readiness flags an expired builder lease', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  db.prepare('INSERT INTO state_builder_lease (singleton_id, owner_id, lease_expires_at) VALUES (1, ?, ?)').run(
    'builder-a',
    900_000, // 已过期(now = 1_000_000)
  );

  const { app } = buildApp(db, { now: () => 1_000_000 });
  const res = await app.inject({ method: 'GET', url: '/health/ready' });
  assert.equal(res.statusCode, 503);
  assert.equal(res.json().checks.builderLease, false);
  db.close();
});
