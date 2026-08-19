import { test } from 'node:test';
import assert from 'node:assert/strict';
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

test('snapshots/latest serves the gzipped envelope with no-store', async () => {
  const db = openDatabase(':memory:');
  const { snapshot } = seedCommands(db);
  const { app } = buildApi(db);

  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/latest' });
  assert.equal(res.statusCode, 200);
  assert.equal(res.headers['content-encoding'], 'gzip');
  assert.equal(res.headers['cache-control'], 'no-store');
  assert.equal(res.headers['x-state-hash'], snapshot.manifest.stateHash);

  const envelope = JSON.parse(gunzipSync(res.rawPayload).toString('utf8'));
  assert.equal(envelope.snapshotVersion, 1);
  assert.equal(envelope.state.tasks['t-1'].title, '任务');
  assert.equal(envelope.state.tasks['t-1'].completed, true);
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
  seedCommands(db);
  const { app } = buildApi(db);

  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/1' });
  assert.equal(res.statusCode, 200);
  assert.match(res.headers.etag, /^"sha256:[0-9a-f]{64}"$/);
  assert.equal(res.headers['cache-control'], 'private, max-age=31536000, immutable');

  const missing = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/99' });
  assert.equal(missing.statusCode, 404);
  assert.equal(missing.json().error, 'SNAPSHOT_NOT_FOUND');

  const bad = await app.inject({ method: 'GET', url: '/tplanner/v3/snapshots/abc' });
  assert.equal(bad.statusCode, 400);
  assert.equal(bad.json().error, 'BAD_SNAPSHOT_VERSION');
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
