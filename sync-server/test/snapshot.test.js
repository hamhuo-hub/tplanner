import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';
import { buildSnapshot, canonicalStateHash } from '../src/materializer/snapshot.js';

const STATE_A = {
  tasks: { 'task-1': { title: '甲', completed: false, lifecycle: 'active', deletedAt: null } },
  customLists: {},
  journals: {},
  goals: {},
  insights: {},
};

// 与 STATE_A 内容相同、仅键序不同(JCS 必须对键序不敏感)
const STATE_A_REORDERED = {
  insights: {},
  goals: {},
  journals: {},
  customLists: {},
  tasks: { 'task-1': { lifecycle: 'active', title: '甲', deletedAt: null, completed: false } },
};

test('canonical stateHash is key-order independent (RFC 8785 JCS)', () => {
  assert.equal(canonicalStateHash(STATE_A), canonicalStateHash(STATE_A_REORDERED));
});

test('stateHash matches ^sha256:[0-9a-f]{64}$', () => {
  assert.match(canonicalStateHash(STATE_A), /^sha256:[0-9a-f]{64}$/);
});

test('different state produces a different hash', () => {
  const other = { ...STATE_A, tasks: { 'task-1': { ...STATE_A.tasks['task-1'], title: '乙' } } };
  assert.notEqual(canonicalStateHash(STATE_A), canonicalStateHash(other));
});

test('rejects non-JSON values instead of silently canonicalizing', () => {
  assert.throws(() => canonicalStateHash({ x: NaN }));
});

test('buildSnapshot produces a schema-conformant manifest', () => {
  const snap = buildSnapshot({
    state: STATE_A,
    snapshotVersion: 1,
    parentVersion: 0,
    serverInstanceId: 'srv-test',
    brokerFromSequence: 501,
    brokerToSequence: 503,
    createdAt: '2026-08-19T00:00:00.000Z',
  });

  assert.equal(snap.manifest.snapshotVersion, 1);
  assert.equal(snap.manifest.parentVersion, 0);
  assert.equal(snap.manifest.schemaVersion, 3);
  assert.equal(snap.manifest.encoding, 'gzip');
  assert.equal(snap.manifest.serverInstanceId, 'srv-test');
  assert.equal(snap.manifest.stateHash, canonicalStateHash(STATE_A));
  assert.match(snap.manifest.compressedHash, /^sha256:[0-9a-f]{64}$/);
  assert.equal(snap.manifest.compressedBytes, snap.compressed.length);
  assert.equal(snap.manifest.uncompressedBytes, Buffer.byteLength(JSON.stringify(snap.envelope), 'utf8'));
});

test('compressed payload gzip-round-trips to the exact envelope', () => {
  const snap = buildSnapshot({
    state: STATE_A,
    snapshotVersion: 1,
    parentVersion: 0,
    serverInstanceId: 'srv-test',
    brokerFromSequence: 501,
    brokerToSequence: 503,
  });

  const envelope = JSON.parse(gunzipSync(snap.compressed).toString('utf8'));
  assert.deepEqual(envelope, snap.envelope);
  assert.equal(envelope.snapshotSchemaVersion, 3);
  assert.deepEqual(envelope.state, STATE_A);
});

test('envelope metadata does not affect stateHash (replay determinism)', () => {
  const a = buildSnapshot({
    state: STATE_A,
    snapshotVersion: 1,
    parentVersion: 0,
    serverInstanceId: 'srv-a',
    brokerFromSequence: 1,
    brokerToSequence: 2,
    createdAt: '2026-08-19T01:00:00.000Z',
  });
  const b = buildSnapshot({
    state: STATE_A,
    snapshotVersion: 2,
    parentVersion: 1,
    serverInstanceId: 'srv-b',
    brokerFromSequence: 3,
    brokerToSequence: 4,
    createdAt: '2026-08-20T02:00:00.000Z',
  });

  assert.equal(a.stateHash, b.stateHash);
  assert.notEqual(a.compressedHash, b.compressedHash); // 信封不同 → 载荷不同
});
