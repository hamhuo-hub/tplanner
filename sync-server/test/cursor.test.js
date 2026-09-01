import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  CursorDecodeError,
  decodeCursor,
  encodeCursor,
  issueCursor,
  resolveCursorSecret,
  validateCursor,
} from '../src/state/cursor.js';

const SECRET = 'test-secret-0123456789abcdef0123456789abcdef';
const CONTEXT = {
  secret: SECRET,
  serverInstanceId: 'srv-cursor-test',
  journalEpoch: 'j-20260901-a',
  schemaVersion: 3,
  deltaVersion: 1,
  principal: 'default',
  minSnapshotVersion: 0,
  headSnapshotVersion: 42,
  snapshotBrokerToSequence: (version) => (version <= 42 ? version * 100 : null),
};

function issueTestCursor(overrides = {}) {
  return issueCursor({
    serverInstanceId: CONTEXT.serverInstanceId,
    journalEpoch: CONTEXT.journalEpoch,
    snapshotVersion: 7,
    brokerToSequence: 700,
    issuedAt: 1_000,
    secret: SECRET,
    ...overrides,
  });
}

function expectReject(cursor, expectedCode, expectedStatus) {
  const result = validateCursor(cursor, CONTEXT);
  assert.equal(result.ok, false);
  assert.equal(result.code, expectedCode);
  assert.equal(result.status, expectedStatus);
}

test('issue → decode → validate round trip succeeds at a commit boundary', () => {
  const cursor = issueTestCursor();
  const result = validateCursor(cursor, CONTEXT);
  assert.equal(result.ok, true);
  assert.equal(result.token.snapshotVersion, 7);
  assert.equal(result.token.brokerToSequence, 700);
  assert.equal(result.token.serverInstanceId, 'srv-cursor-test');
  assert.equal(result.token.journalEpoch, 'j-20260901-a');
  assert.equal(result.token.schemaVersion, 3);
  assert.equal(result.token.deltaVersion, 1);
  assert.equal(result.token.principal, 'default');
  assert.equal(result.token.issuedAt, 1_000);

  const decoded = decodeCursor(cursor, SECRET);
  assert.equal(decoded.snapshotVersion, 7);
});

test('cursor is opaque: base64url payload.signature, no plaintext JSON', () => {
  const cursor = issueTestCursor();
  assert.match(cursor, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
  assert.ok(!cursor.includes('snapshotVersion'));
  assert.throws(() => JSON.parse(cursor));
});

test('any tampered payload or signature byte is rejected with 400', () => {
  const cursor = issueTestCursor();
  const [payloadPart, signature] = cursor.split('.');

  const flip = (s) => {
    const ch = s[0] === 'A' ? 'B' : 'A';
    return ch + s.slice(1);
  };
  expectReject(`${flip(payloadPart)}.${signature}`, 'CURSOR_INVALID', 400);
  expectReject(`${payloadPart}.${flip(signature)}`, 'CURSOR_INVALID', 400);
  expectReject(cursor.slice(0, -1), 'CURSOR_INVALID', 400);
  expectReject('not-a-cursor', 'CURSOR_INVALID', 400);
  expectReject('', 'CURSOR_INVALID', 400);
});

test('wrong secret validation', () => {
  const cursor = issueTestCursor();
  const result = validateCursor(cursor, { ...CONTEXT, secret: 'another-secret-0123456789abcdef0123456789' });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'CURSOR_INVALID');
  assert.equal(result.status, 400);
});

test('unsupported cursor version is rejected with 400', () => {
  const cursor = encodeCursor({
    v: 2,
    serverInstanceId: CONTEXT.serverInstanceId,
    journalEpoch: CONTEXT.journalEpoch,
    snapshotVersion: 7,
    brokerToSequence: 700,
    schemaVersion: 3,
    deltaVersion: 1,
    principal: 'default',
    issuedAt: 1_000,
  }, SECRET);
  expectReject(cursor, 'CURSOR_VERSION_UNSUPPORTED', 400);
});

test('serverInstanceId mismatch forces full bootstrap (410)', () => {
  expectReject(
    issueTestCursor({ serverInstanceId: 'srv-other-universe' }),
    'CURSOR_SERVER_CHANGED',
    410,
  );
});

test('journalEpoch mismatch forces full snapshot (410)', () => {
  expectReject(
    issueTestCursor({ journalEpoch: 'j-20260801-a' }),
    'CURSOR_EPOCH_EXPIRED',
    410,
  );
});

test('schemaVersion and deltaVersion mismatch fall back (410)', () => {
  expectReject(
    issueTestCursor({ schemaVersion: 2 }),
    'CURSOR_SCHEMA_CHANGED',
    410,
  );
  expectReject(
    issueTestCursor({ deltaVersion: 2 }),
    'CURSOR_DELTA_UNSUPPORTED',
    410,
  );
});

test('cursor below min_snapshot_version is expired (410)', () => {
  const cursor = issueTestCursor({ snapshotVersion: 5, brokerToSequence: 500 });
  const result = validateCursor(cursor, { ...CONTEXT, minSnapshotVersion: 6 });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'CURSOR_EXPIRED');
  assert.equal(result.status, 410);

  // 恰好在 min 上仍然可用(需要 commit min+1 起)。
  const atMin = issueTestCursor({ snapshotVersion: 6, brokerToSequence: 600 });
  assert.equal(validateCursor(atMin, { ...CONTEXT, minSnapshotVersion: 6 }).ok, true);
});

test('cursor ahead of the journal head is rejected (410)', () => {
  expectReject(
    issueTestCursor({ snapshotVersion: 43, brokerToSequence: 4300 }),
    'CURSOR_AHEAD_OF_SERVER',
    410,
  );
});

test('coverage mismatch with the snapshot row is rejected (410)', () => {
  expectReject(
    issueTestCursor({ snapshotVersion: 7, brokerToSequence: 701 }),
    'CURSOR_COVERAGE_MISMATCH',
    410,
  );
  // 匹配时通过
  assert.equal(validateCursor(issueTestCursor({ snapshotVersion: 7, brokerToSequence: 700 }), CONTEXT).ok, true);
});

test('other-principal and other-device cursors are rejected with 403', () => {
  expectReject(
    issueTestCursor({ principal: 'other-tenant' }),
    'FORBIDDEN',
    403,
  );
  const deviceScoped = issueTestCursor({ deviceId: 'dev-a' });
  const result = validateCursor(deviceScoped, { ...CONTEXT, deviceId: 'dev-b' });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'FORBIDDEN');
  assert.equal(result.status, 403);
  // 相同 device scope 通过;未提供 device 时不做 device 校验(dataset 级 cursor)
  assert.equal(validateCursor(deviceScoped, { ...CONTEXT, deviceId: 'dev-a' }).ok, true);
  assert.equal(validateCursor(deviceScoped, CONTEXT).ok, true);
});

test('issuedAt age beyond maxAgeMs is expired (410)', () => {
  const cursor = issueTestCursor();
  const result = validateCursor(cursor, {
    ...CONTEXT,
    maxAgeMs: 60_000,
    now: () => 1_000 + 60_001,
  });
  assert.equal(result.ok, false);
  assert.equal(result.code, 'CURSOR_AGE_EXPIRED');
  assert.equal(result.status, 410);
  assert.equal(validateCursor(cursor, { ...CONTEXT, maxAgeMs: 60_000, now: () => 1_000 + 59_999 }).ok, true);
});

test('malformed payload fields are rejected as invalid', () => {
  const bad = encodeCursor({
    v: 1,
    serverInstanceId: 'srv-x',
    journalEpoch: 'j-x',
    snapshotVersion: 'not-a-number',
    brokerToSequence: 1,
    principal: 'default',
    issuedAt: 1,
  }, SECRET);
  expectReject(bad, 'CURSOR_INVALID', 400);
  assert.throws(() => decodeCursor('x', SECRET), CursorDecodeError);
  assert.throws(() => encodeCursor({}, 'short'));
});

test('resolveCursorSecret honors the environment override and persists a stable random file secret', () => {
  assert.equal(resolveCursorSecret('/any/path.db', 'env-secret-0123456789abcdef'), 'env-secret-0123456789abcdef');

  const dir = mkdtempSync(join(tmpdir(), 'tplanner-cursor-secret-'));
  try {
    const dbPath = join(dir, 'tplanner.db');
    const first = resolveCursorSecret(dbPath, undefined);
    assert.equal(typeof first, 'string');
    assert.ok(first.length >= 32);
    assert.equal(readFileSync(`${dbPath}.cursor-secret`, 'utf8').trim(), first);
    const second = resolveCursorSecret(dbPath, undefined);
    assert.equal(second, first, 'secret must be stable across restarts');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
