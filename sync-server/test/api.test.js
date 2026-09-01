import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { gunzipSync } from 'node:zlib';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';
import { createStore } from '../src/api/store.js';
import { buildServer } from '../src/api/app.js';

function buildApi(db) {
  const store = createStore(db, { serverInstanceId: 'srv-api-test' });
  const app = buildServer({
    publisher: { publish: async () => ({}) },
    validateBatch: () => null,
    store,
    health: {
      readiness: async () => ({ ok: true, status: 'ready' }),
      status: async () => ({}),
    },
  });
  return { app, store };
}

function seedCommands(db) {
  const m = createMaterializer({ db, applyCommand, serverInstanceId: 'srv-api-test' });
  return m.processIntegrationBatch([
    {
      brokerSequence: 1,
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
      brokerSequence: 2,
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

test('capabilities reports protocol, server, and latest snapshot version', async () => {
  const db = openDatabase(':memory:');
  const { app } = buildApi(db);

  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/capabilities' });
  assert.equal(res.statusCode, 200);
  const body = res.json();
  assert.equal(body.softwareVersion, '8.0.0');
  assert.equal(body.protocolVersion, 3);
  assert.equal(body.schemaVersion, 3);
  assert.equal(body.serverInstanceId, 'srv-api-test');
  assert.equal(body.latestSnapshotVersion, 0); // 尚无快照
  assert.equal(body.maxBatchCommands, 100);

  seedCommands(db);
  const after = await app.inject({ method: 'GET', url: '/tplanner/v3/capabilities' });
  assert.equal(after.json().latestSnapshotVersion, 1);
  db.close();
});

test('receipts endpoint returns results after the cursor', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  const { app } = buildApi(db);

  const res = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/receipts?deviceId=dev-1&afterClientSequence=0',
  });
  assert.equal(res.statusCode, 200);
  const body = res.json();
  assert.equal(body.acceptedThrough, 2);
  assert.equal(body.results.length, 2);
  assert.equal(body.results[0].commandId, 'c-1');
  assert.equal(body.results[0].status, 'APPLIED');
  assert.equal(body.results[1].status, 'APPLIED');

  const after = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/receipts?deviceId=dev-1&afterClientSequence=1',
  });
  assert.equal(after.json().results.length, 1);

  const missingDevice = await app.inject({ method: 'GET', url: '/tplanner/v3/receipts?afterClientSequence=0' });
  assert.equal(missingDevice.statusCode, 400);
  assert.equal(missingDevice.json().error, 'DEVICE_ID_REQUIRED');
  db.close();
});

test('snapshots/latest serves a JSON manifest with no-store', async () => {
  const db = openDatabase(':memory:');
  const { snapshot } = seedCommands(db);
  const { app } = buildApi(db);

  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/latest' });
  assert.equal(res.statusCode, 200);
  assert.match(res.headers['content-type'], /^application\/json/);
  assert.equal(res.headers['content-encoding'], undefined);
  assert.equal(res.headers['cache-control'], 'no-store');
  assert.equal(res.headers['x-state-hash'], snapshot.manifest.stateHash);
  assert.deepEqual(res.json(), snapshot.manifest);
  db.close();
});

test('snapshots/latest returns 404 before the first snapshot', async () => {
  const db = openDatabase(':memory:');
  const { app } = buildApi(db);
  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/latest' });
  assert.equal(res.statusCode, 404);
  assert.equal(res.json().error, 'SNAPSHOT_NOT_FOUND');
  db.close();
});

test('snapshots/:version serves immutable bytes with ETag; 404 for unknown', async () => {
  const db = openDatabase(':memory:');
  const { snapshot } = seedCommands(db);
  const { app } = buildApi(db);

  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/1' });
  assert.equal(res.statusCode, 200);
  assert.match(res.headers.etag, /^"sha256:[0-9a-f]{64}"$/);
  assert.equal(res.headers['cache-control'], 'private, max-age=31536000, immutable');
  assert.equal(res.headers['content-type'], 'application/gzip');
  assert.equal(res.headers['content-encoding'], undefined);
  assert.equal(
    `sha256:${createHash('sha256').update(res.rawPayload).digest('hex')}`,
    snapshot.manifest.compressedHash,
  );
  const envelope = JSON.parse(gunzipSync(res.rawPayload).toString('utf8'));
  assert.equal(envelope.snapshotVersion, 1);
  assert.equal(envelope.state.tasks['t-1'].title, '任务');
  assert.equal(envelope.state.tasks['t-1'].completed, true);

  const missing = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/99' });
  assert.equal(missing.statusCode, 404);
  assert.equal(missing.json().error, 'SNAPSHOT_NOT_FOUND');

  const bad = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/abc' });
  assert.equal(bad.statusCode, 400);
  assert.equal(bad.json().error, 'BAD_SNAPSHOT_VERSION');
  db.close();
});

test('CORS preflight and responses allow Web, local development, and Electron origins', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  const { app } = buildApi(db);

  const preflight = await app.inject({
    method: 'OPTIONS',
    url: '/tplanner/v3/command-batches',
    headers: {
      origin: 'https://plan.hamhuo.top',
      'access-control-request-method': 'POST',
      'access-control-request-headers': [
        'content-type', 'authorization', 'idempotency-key', 'cache-control', 'if-none-match',
      ].join(', '),
    },
  });
  assert.equal(preflight.statusCode, 204);
  assert.equal(preflight.headers['access-control-allow-origin'], 'https://plan.hamhuo.top');
  assert.equal(preflight.headers['access-control-allow-methods'], 'GET, POST, OPTIONS');
  for (const header of ['Authorization', 'Idempotency-Key', 'Cache-Control', 'If-None-Match']) {
    assert.match(preflight.headers['access-control-allow-headers'], new RegExp(header, 'i'));
  }

  for (const origin of ['http://localhost:5173', 'https://127.0.0.1:4173', 'null', 'file://']) {
    const res = await app.inject({
      method: 'GET',
      url: '/tplanner/v3/snapshots/latest',
      headers: { origin },
    });
    assert.equal(res.statusCode, 200);
    assert.equal(res.headers['access-control-allow-origin'], origin);
    assert.equal(
      res.headers['access-control-expose-headers'],
      'ETag, X-Snapshot-Version, X-State-Hash',
    );
  }

  const denied = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/snapshots/latest',
    headers: { origin: 'https://example.invalid' },
  });
  assert.equal(denied.headers['access-control-allow-origin'], undefined);
  db.close();
});

test('notifications returns the current version and hash after its wait times out', async () => {
  const db = openDatabase(':memory:');
  const { snapshot } = seedCommands(db);
  const { app } = buildApi(db);
  const startedAt = Date.now();

  const res = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/notifications?afterVersion=1&wait=20',
  });
  const elapsedMs = Date.now() - startedAt;

  assert.equal(res.statusCode, 200);
  assert.ok(elapsedMs >= 10, `long poll returned too early (${elapsedMs}ms)`);
  assert.ok(elapsedMs < 500, `long poll exceeded its deadline (${elapsedMs}ms)`);
  assert.deepEqual(res.json(), {
    latestVersion: 1,
    stateHash: snapshot.manifest.stateHash,
  });
  db.close();
});

test('notifications wakes when SQLite publishes a newer latest snapshot', async () => {
  const db = openDatabase(':memory:');
  const { app } = buildApi(db);
  await app.ready();

  const pending = app.inject({
    method: 'GET',
    url: '/tplanner/v3/notifications?afterVersion=0&wait=1000',
  });
  await new Promise((resolve) => setTimeout(resolve, 75));
  const { snapshot } = seedCommands(db);
  const res = await pending;

  assert.equal(res.statusCode, 200);
  assert.deepEqual(res.json(), {
    latestVersion: 1,
    stateHash: snapshot.manifest.stateHash,
  });
  db.close();
});

test('notifications rejects invalid cursors and excessive waits', async () => {
  const db = openDatabase(':memory:');
  const { app } = buildApi(db);

  const badCursor = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/notifications?afterVersion=-1&wait=0',
  });
  assert.equal(badCursor.statusCode, 400);
  assert.equal(badCursor.json().error, 'BAD_AFTER_VERSION');

  const badWait = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/notifications?afterVersion=0&wait=30001',
  });
  assert.equal(badWait.statusCode, 400);
  assert.equal(badWait.json().error, 'BAD_WAIT');
  db.close();
});

test('V1 dataset routes are retired while /health remains a V3 readiness alias', async () => {
  const db = openDatabase(':memory:');
  const { app } = buildApi(db);

  for (const url of [
    '/tplanner/events',
    '/tplanner/journals',
    '/tplanner/goals',
    '/tplanner/insights',
    '/tplanner/changes?since=0',
    '/tplanner/time',
  ]) {
    const res = await app.inject({ method: 'GET', url });
    assert.equal(res.statusCode, 404, `${url} must no longer be registered`);
  }

  const write = await app.inject({ method: 'PUT', url: '/tplanner/events', payload: [] });
  assert.equal(write.statusCode, 404);

  const health = await app.inject({ method: 'GET', url: '/health' });
  assert.equal(health.statusCode, 200);
  assert.deepEqual(health.json(), { ok: true, status: 'ready' });
  db.close();
});

test('conditional download returns 304 when If-None-Match equals the compressedHash', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  const { app } = buildApi(db);

  const first = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/1' });
  const etag = first.headers.etag;

  const notModified = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/snapshots/1',
    headers: { 'if-none-match': etag },
  });
  assert.equal(notModified.statusCode, 304);
  assert.equal(notModified.rawPayload.length, 0);
  assert.equal(notModified.headers.etag, etag);

  // latest 同样支持 304;不同 hash 则返回 200 + 新载荷
  const latest = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/snapshots/latest',
    headers: { 'if-none-match': etag },
  });
  assert.equal(latest.statusCode, 304);

  const stale = await app.inject({
    method: 'GET',
    url: '/tplanner/v3/snapshots/1',
    headers: { 'if-none-match': '"sha256:' + '0'.repeat(64) + '"' },
  });
  assert.equal(stale.statusCode, 200);
  assert.ok(stale.rawPayload.length > 0);
  db.close();
});

test('snapshot payloads are cached per version (same Buffer instance)', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  const { store } = buildApi(db);

  const a = store.snapshotPayload(1);
  const b = store.snapshotPayload(1);
  assert.ok(Buffer.isBuffer(a));
  assert.equal(a, b); // 缓存命中:同一 Buffer 引用,不重复读 BLOB
  db.close();
});

test('snapshot-acks records device progress', async () => {
  const db = openDatabase(':memory:');
  seedCommands(db);
  const { app } = buildApi(db);

  const res = await app.inject({
    method: 'POST',
    url: '/tplanner/v3/devices/dev-1/snapshot-acks',
    payload: { version: 1, stateHash: `sha256:${'a'.repeat(64)}` },
  });
  assert.equal(res.statusCode, 202);
  assert.equal(res.json().state, 'ACK_RECORDED');

  const row = db.prepare("SELECT * FROM device_progress WHERE device_id = 'dev-1'").get();
  assert.equal(row.installed_snapshot_version, 1);
  assert.equal(row.installed_snapshot_hash, `sha256:${'a'.repeat(64)}`);
  assert.equal(row.protocol_version, 3);
  assert.ok(row.last_seen_at > 0);

  const bad = await app.inject({
    method: 'POST',
    url: '/tplanner/v3/devices/dev-1/snapshot-acks',
    payload: { version: 'x' },
  });
  assert.equal(bad.statusCode, 400);
  assert.equal(bad.json().error, 'SCHEMA_UNSUPPORTED');
  db.close();
});
