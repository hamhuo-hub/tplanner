import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer, ensureBootstrapSnapshot } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';
import { createStore } from '../src/api/store.js';
import { buildServer } from '../src/api/app.js';
import { createChangesService } from '../src/api/changes.js';
import { createMetrics } from '../src/api/metrics.js';
import { createMonitoring } from '../src/api/monitoring.js';
import { issueCursor } from '../src/state/cursor.js';

const SERVER_ID = 'srv-changes-test';
const SECRET = 'changes-test-secret-0123456789abcdef0123456789';

const protocolPath = (name) =>
  fileURLToPath(new URL(`../../sync-v3/protocol/v3/${name}`, import.meta.url));

function entry(brokerSequence, deviceId, command, batchId = 'b1') {
  return { brokerSequence, deviceId, batchId, command };
}

function cmd(type, aggregateId, args, commandId, clientSequence) {
  return { commandId, clientSequence, type, aggregateId, arguments: args ?? {} };
}

// v1 bootstrap(空 commit)→ v2 双实体 → v3 NOOP(空)→ v4 REJECTED(空)→ v5 goal → v6 journal
function seedHistory(db) {
  ensureBootstrapSnapshot(db, { serverInstanceId: SERVER_ID, now: () => 1 });
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
    entry(2, 'dev-1', cmd('list.create', 'l1', { title: 'l' }, 'c2', 2)),
  ]);
  m.processIntegrationBatch([
    entry(3, 'dev-1', cmd('task.setTitle', 't1', { title: 'a' }, 'c3', 3)),
  ]);
  m.processIntegrationBatch([
    entry(4, 'dev-1', cmd('task.unknownCommand', 't1', {}, 'c4', 4)),
  ]);
  m.processIntegrationBatch([
    entry(5, 'dev-1', cmd('goal.create', 'g1', { title: 'g' }, 'c5', 5)),
  ]);
  m.processIntegrationBatch([
    entry(6, 'dev-1', cmd('journal.setText', 'j1', { text: 'd' }, 'c6', 6)),
  ]);
  return 6;
}

function buildApi(db, { enabled = true, retention = {}, now = Date.now } = {}) {
  const metrics = createMetrics();
  const store = createStore(db, { serverInstanceId: SERVER_ID });
  const changes = createChangesService({
    db,
    serverInstanceId: SERVER_ID,
    cursorSecret: SECRET,
    enabled,
    retention,
    metrics,
    now,
  });
  const app = buildServer({
    publisher: { publish: async () => ({}) },
    validateBatch: () => null,
    store,
    health: {
      readiness: async () => ({ ok: true }),
      status: async () => ({}),
    },
    changes,
  });
  return { app, changes, metrics };
}

const getChanges = (app, cursor, maxCommits) => app.inject({
  method: 'GET',
  url: `/tplanner/v3/changes?cursor=${encodeURIComponent(cursor)}${maxCommits === undefined ? '' : `&maxCommits=${maxCommits}`}`,
});

test('capabilities exposes delta-v1 as an opt-in downlink mode with journal coordinates', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app } = buildApi(db);

  const body = (await app.inject({ method: 'GET', url: '/tplanner/v3/capabilities' })).json();
  assert.deepEqual(body.downlinkModes, ['snapshot', 'delta-v1']);
  assert.equal(body.delta.version, 1);
  assert.equal(body.delta.maxCommits, 100);
  assert.equal(body.delta.headSnapshotVersion, 6);
  assert.equal(body.delta.minSnapshotVersion, 0);
  assert.ok(body.delta.journalEpoch.length > 0);
  db.close();
});

test('a valid cursor returns ordered contiguous commits and preserves empty commits', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db);
  const cursor = changes.issueCursorAt({ snapshotVersion: 1, brokerToSequence: 0 });

  const res = await getChanges(app, cursor);
  assert.equal(res.statusCode, 200);
  const body = res.json();
  assert.equal(body.protocolVersion, 3);
  assert.equal(body.deltaVersion, 1);
  assert.equal(body.schemaVersion, 3);
  assert.equal(body.serverInstanceId, SERVER_ID);
  assert.equal(body.fromCursor, cursor);
  assert.notEqual(body.toCursor, cursor);
  assert.equal(body.headSnapshotVersion, 6);
  assert.equal(body.hasMore, false);
  assert.deepEqual(body.commits.map((c) => c.snapshotVersion), [2, 3, 4, 5, 6]);

  const byVersion = Object.fromEntries(body.commits.map((c) => [c.snapshotVersion, c]));
  assert.equal(byVersion[2].changes.length, 2, 'v2 must carry both authoritative puts');
  assert.deepEqual(byVersion[3].changes, [], 'NOOP empty commit must be preserved');
  assert.deepEqual(byVersion[4].changes, [], 'REJECTED empty commit must be preserved');
  assert.equal(byVersion[2].changes[0].type, 'task.put');
  assert.equal(byVersion[2].changes[0].value.lifecycle, 'active');
  assert.equal(byVersion[3].parentVersion, 2);
  assert.equal(byVersion[4].parentVersion, 3);
  db.close();
});

test('pagination never splits a commit and produces no gaps or duplicates', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db);

  let cursor = changes.issueCursorAt({ snapshotVersion: 1, brokerToSequence: 0 });
  const collected = [];
  const pages = [];
  let hasMore = true;
  while (hasMore) {
    const res = await getChanges(app, cursor, 2);
    assert.equal(res.statusCode, 200);
    const body = res.json();
    pages.push(body);
    collected.push(...body.commits.map((c) => c.snapshotVersion));
    cursor = body.toCursor;
    hasMore = body.hasMore;
  }

  assert.deepEqual(collected, [2, 3, 4, 5, 6], 'contiguous, no gaps, no duplicates');
  assert.deepEqual(pages.map((p) => p.commits.length), [2, 2, 1]);
  assert.equal(pages[0].hasMore, true);
  assert.equal(pages[2].hasMore, false);

  // 每页边界都落在 commit 上:后一页第一个 commit 的 parent 是前一页最后一个 commit
  for (let i = 1; i < pages.length; i += 1) {
    const prevLast = pages[i - 1].commits.at(-1).snapshotVersion;
    assert.equal(pages[i].commits[0].parentVersion, prevLast);
  }
  // toCursor 永远等于最后返回的 commit
  for (const page of pages) {
    const lastCommit = page.commits.at(-1);
    const decoded = page.toCursor.split('.')[0];
    assert.ok(Buffer.from(decoded, 'base64url').toString('utf8').includes(`"snapshotVersion":${lastCommit.snapshotVersion}`));
  }
  db.close();
});

test('no-change response keeps the cursor stable and emits an empty commit list', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db);
  const cursor = changes.issueCursorAt({ snapshotVersion: 6, brokerToSequence: 6 });

  const res = await getChanges(app, cursor);
  assert.equal(res.statusCode, 200);
  const body = res.json();
  assert.deepEqual(body.commits, []);
  assert.equal(body.hasMore, false);
  assert.equal(body.toCursor, cursor, 'no-change response must keep the cursor stable');
  assert.equal(body.fromCursor, cursor);
  assert.equal(body.headSnapshotVersion, 6);
  db.close();
});

test('missing cursor, forged cursor and other-principal cursor are rejected', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db);

  const noCursor = await app.inject({ method: 'GET', url: '/tplanner/v3/changes' });
  assert.equal(noCursor.statusCode, 400);
  assert.equal(noCursor.json().error, 'CURSOR_REQUIRED');

  const forged = await getChanges(app, 'not-a-valid-cursor');
  assert.equal(forged.statusCode, 400);
  assert.equal(forged.json().error, 'CURSOR_INVALID');

  const otherTenant = issueCursor({
    serverInstanceId: SERVER_ID,
    journalEpoch: (await app.inject({ method: 'GET', url: '/tplanner/v3/capabilities' })).json().delta.journalEpoch,
    snapshotVersion: 1,
    brokerToSequence: 0,
    principal: 'other-tenant',
    issuedAt: 1,
    secret: SECRET,
  });
  const forbidden = await getChanges(app, otherTenant);
  assert.equal(forbidden.statusCode, 403);
  assert.equal(forbidden.json().error, 'FORBIDDEN');

  const invalidMax = await getChanges(app, changes.issueCursorAt({ snapshotVersion: 1, brokerToSequence: 0 }), 0);
  assert.equal(invalidMax.statusCode, 400);
  assert.equal(invalidMax.json().error, 'BAD_MAX_COMMITS');
  db.close();
});

test('epoch mismatch, expired and ahead-of-head cursors return 410 with FULL_SNAPSHOT recovery', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db);

  const epochMismatch = issueCursor({
    serverInstanceId: SERVER_ID,
    journalEpoch: 'j-19990101-a',
    snapshotVersion: 1,
    brokerToSequence: 0,
    issuedAt: 1,
    secret: SECRET,
  });
  const epochRes = await getChanges(app, epochMismatch);
  assert.equal(epochRes.statusCode, 410);
  assert.deepEqual(epochRes.json(), {
    error: 'CURSOR_EPOCH_EXPIRED',
    recovery: 'FULL_SNAPSHOT',
    latestSnapshotVersion: 6,
  });

  const ahead = await getChanges(app, changes.issueCursorAt({ snapshotVersion: 99, brokerToSequence: 9_900 }));
  assert.equal(ahead.statusCode, 410);
  assert.equal(ahead.json().error, 'CURSOR_AHEAD_OF_SERVER');
  assert.equal(ahead.json().recovery, 'FULL_SNAPSHOT');

  // retention 剪掉 1..3 后,老 cursor 过期
  const { app: pruningApp } = buildApi(db, { retention: { keepCommits: 3 } });
  const oldCursor = changes.issueCursorAt({ snapshotVersion: 1, brokerToSequence: 0 });
  const expired = await getChanges(pruningApp, oldCursor);
  assert.equal(expired.statusCode, 410);
  assert.equal(expired.json().error, 'CURSOR_EXPIRED');
  assert.equal(expired.json().recovery, 'FULL_SNAPSHOT');
  assert.equal(expired.json().latestSnapshotVersion, 6);
  db.close();
});

test('retention advances min_snapshot_version monotonically and keeps the tail servable', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db, { retention: { keepCommits: 3 } });

  // 第一次请求触发 prune:cutoff = head(6) - 3 = 3
  const atMin = changes.issueCursorAt({ snapshotVersion: 3, brokerToSequence: 3 });
  const res = await getChanges(app, atMin);
  assert.equal(res.statusCode, 200);
  assert.deepEqual(res.json().commits.map((c) => c.snapshotVersion), [4, 5, 6]);

  const meta = db.prepare('SELECT min_snapshot_version FROM sync_journal_meta WHERE singleton_id = 1').get();
  assert.equal(meta.min_snapshot_version, 3);
  assert.deepEqual(
    db.prepare('SELECT snapshot_version FROM change_commits ORDER BY snapshot_version').all().map((r) => r.snapshot_version),
    [4, 5, 6],
  );

  // 幂等:再次请求不再推进
  await getChanges(app, atMin);
  assert.equal(
    db.prepare('SELECT min_snapshot_version FROM sync_journal_meta WHERE singleton_id = 1').get().min_snapshot_version,
    3,
  );
  db.close();
});

test('disabling delta flips capabilities to snapshot-only and /changes to 410', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db, { enabled: false });

  const caps = (await app.inject({ method: 'GET', url: '/tplanner/v3/capabilities' })).json();
  assert.deepEqual(caps.downlinkModes, ['snapshot']);

  const cursor = changes.issueCursorAt({ snapshotVersion: 1, brokerToSequence: 0 });
  const res = await getChanges(app, cursor);
  assert.equal(res.statusCode, 410);
  assert.deepEqual(res.json(), {
    error: 'DELTA_DISABLED',
    recovery: 'FULL_SNAPSHOT',
    latestSnapshotVersion: 6,
  });
  db.close();
});

test('a buildServer without a changes service serves snapshot-only capabilities', async () => {
  const db = openDatabase(':memory:');
  const store = createStore(db, { serverInstanceId: SERVER_ID });
  const app = buildServer({
    publisher: { publish: async () => ({}) },
    validateBatch: () => null,
    store,
    health: { readiness: async () => ({ ok: true }), status: async () => ({}) },
  });

  const caps = (await app.inject({ method: 'GET', url: '/tplanner/v3/capabilities' })).json();
  assert.deepEqual(caps.downlinkModes, ['snapshot']);

  const res = await app.inject({ method: 'GET', url: '/tplanner/v3/changes?cursor=x' });
  assert.equal(res.statusCode, 410);
  assert.equal(res.json().error, 'DELTA_DISABLED');
  db.close();
});

test('request, fallback, lag and size telemetry is recorded', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes, metrics } = buildApi(db, { retention: { keepCommits: 3 }, now: () => 1_000_000 });

  // 第一个请求成功(cursor 恰在保留边界上),随后一个过期 cursor 触发 fallback 计数。
  const ok = await getChanges(app, changes.issueCursorAt({ snapshotVersion: 3, brokerToSequence: 3 }));
  assert.equal(ok.statusCode, 200);
  await getChanges(app, changes.issueCursorAt({ snapshotVersion: 1, brokerToSequence: 0 }));

  const counters = metrics.snapshot().counters;
  assert.equal(counters.delta_requests_total, 1);
  assert.equal(counters.delta_commits_total, 3);
  assert.ok(counters.delta_response_bytes > 0);
  assert.equal(counters['snapshot_fallback_total:CURSOR_EXPIRED'], 1, 'expired cursor must count a fallback');

  const gauges = metrics.snapshot().gauges;
  assert.ok(Number.isInteger(gauges.cursor_lag_versions));
  assert.ok(Number.isInteger(gauges.cursor_age_seconds));
  db.close();
});

test('status endpoint reports journal storage gauges and counters', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { metrics } = buildApi(db);
  metrics.increment('delta_requests_total');
  const health = createMonitoring({
    db,
    jsm: { streams: { info: async () => ({ state: { last_seq: 0 } }) } },
    serverInstanceId: SERVER_ID,
    metrics,
  });

  const status = await health.status();
  assert.equal(status.storage.journalHeadVersion, 6);
  assert.equal(status.storage.journalMinVersion, 0);
  assert.equal(status.storage.journalCommits, 6);
  assert.ok(status.storage.journalPayloadBytes > 0);
  assert.equal(status.storage.snapshotCount, 6);
  assert.ok(status.storage.snapshotBytes > 0);
  assert.equal(status.metrics.counters.delta_requests_total, 1);
  db.close();
});

test('a real delta response validates against delta-response.schema.json', async () => {
  const db = openDatabase(':memory:');
  seedHistory(db);
  const { app, changes } = buildApi(db);

  const [snapshotSchema, deltaSchema] = await Promise.all([
    readFile(protocolPath('snapshot.schema.json'), 'utf8').then(JSON.parse),
    readFile(protocolPath('delta-response.schema.json'), 'utf8').then(JSON.parse),
  ]);
  const ajv = new Ajv2020({ strict: false });
  ajv.addSchema(snapshotSchema);
  const validate = ajv.compile(deltaSchema);

  const body = (await getChanges(app, changes.issueCursorAt({ snapshotVersion: 1, brokerToSequence: 0 }))).json();
  assert.equal(validate(body), true, ajv.errorsText(validate.errors));

  // 未知 change type 违反 oneOf → 校验失败(客户端会因此走 snapshot fallback)
  const tampered = JSON.parse(JSON.stringify(body));
  tampered.commits[0].changes[0].type = 'task.title.patch';
  assert.equal(validate(tampered), false);
  db.close();
});
