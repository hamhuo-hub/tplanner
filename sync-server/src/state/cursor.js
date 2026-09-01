// Signed opaque journal cursor(见 docs/sync-v3.md §9.3)。
//
// 客户端同步位置的唯一合法表示:服务端签发、HMAC-SHA256 签名、客户端只保存
// 字符串、绝不解析。cursor 永远落在 commit 边界 —— payload 只携带
// snapshotVersion + brokerToSequence,不表达任何 commit 内部位置,因此
// /changes 分页与客户端 crash recovery 不必处理"半 commit"。
//
// 校验失败不猜测、不修补:签名/结构错 → 400,scope 不符 → 403,其余一切
// (server 变化、epoch 过期、schema/delta 版本不符、太旧、超前、coverage
// 不符、超过 maxAge)→ 410,客户端一律走 full snapshot。
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export const CURSOR_VERSION = 1;
export const DEFAULT_PRINCIPAL = 'default';

export class CursorDecodeError extends Error {
  constructor(message) {
    super(message);
    this.name = 'CursorDecodeError';
  }
}

function sign(payloadJson, secret) {
  return createHmac('sha256', secret).update(payloadJson).digest('base64url');
}

// 签名覆盖原始 JSON 字节(与 encodeCursor 一致),而非 base64url 传输形态。
function verifySignature(payloadJson, signature, secret) {
  const expected = Buffer.from(sign(payloadJson, secret), 'utf8');
  const actual = Buffer.from(signature, 'utf8');
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

/**
 * 传输格式:base64url(payloadJson) + "." + base64url(HMAC-SHA256(payloadJson))。
 * payload 本身也是任意 JSON;cursor 对客户端保持 opaque。
 */
export function encodeCursor(payload, secret) {
  if (typeof secret !== 'string' || secret.length < 16) {
    throw new Error('cursor secret must be a string of at least 16 characters');
  }
  const json = JSON.stringify(payload);
  const payloadPart = Buffer.from(json, 'utf8').toString('base64url');
  return `${payloadPart}.${sign(json, secret)}`;
}

export function decodeCursor(rawCursor, secret) {
  if (typeof rawCursor !== 'string' || rawCursor === '') {
    throw new CursorDecodeError('cursor must be a non-empty string');
  }
  const dot = rawCursor.lastIndexOf('.');
  if (dot <= 0 || dot === rawCursor.length - 1) {
    throw new CursorDecodeError('cursor is not in payload.signature form');
  }
  const payloadPart = rawCursor.slice(0, dot);
  const signature = rawCursor.slice(dot + 1);
  let json;
  try {
    json = Buffer.from(payloadPart, 'base64url').toString('utf8');
  } catch {
    throw new CursorDecodeError('cursor payload is not valid base64url');
  }
  if (!verifySignature(json, signature, secret)) {
    throw new CursorDecodeError('cursor signature does not verify');
  }
  let payload;
  try {
    payload = JSON.parse(json);
  } catch {
    throw new CursorDecodeError('cursor payload is not valid JSON');
  }
  if (
    typeof payload !== 'object'
    || payload === null
    || Array.isArray(payload)
    || !Number.isInteger(payload.v)
    || !Number.isInteger(payload.snapshotVersion)
    || payload.snapshotVersion < 0
    || !Number.isInteger(payload.brokerToSequence)
    || payload.brokerToSequence < 0
    || typeof payload.serverInstanceId !== 'string'
    || typeof payload.journalEpoch !== 'string'
    || typeof payload.principal !== 'string'
    || (payload.issuedAt !== undefined && typeof payload.issuedAt !== 'number')
  ) {
    throw new CursorDecodeError('cursor payload fields are malformed');
  }
  return payload;
}

/**
 * 服务端在 commit 边界签发 cursor。snapshotVersion 必须对应一个已提交的
 * snapshot;brokerToSequence 是该 snapshot 的 coverage watermark。
 */
export function issueCursor({
  serverInstanceId,
  journalEpoch,
  snapshotVersion,
  brokerToSequence,
  schemaVersion = 3,
  deltaVersion = 1,
  principal = DEFAULT_PRINCIPAL,
  deviceId = null,
  issuedAt = Date.now(),
  secret,
}) {
  return encodeCursor({
    v: CURSOR_VERSION,
    serverInstanceId,
    journalEpoch,
    snapshotVersion,
    brokerToSequence,
    schemaVersion,
    deltaVersion,
    principal,
    ...(deviceId === null ? {} : { deviceId }),
    issuedAt,
  }, secret);
}

/**
 * 校验 cursor 并返回结构化结果(绝不抛业务异常,HTTP 层直接映射):
 *   { ok: true, token } 或 { ok: false, status, code }。
 * snapshotBrokerToSequence(version) 返回该 snapshot 的 broker_to_sequence,
 * 不存在时返回 null(coverage 校验)。
 */
export function validateCursor(
  rawCursor,
  {
    secret,
    serverInstanceId,
    journalEpoch,
    schemaVersion = 3,
    deltaVersion = 1,
    principal = DEFAULT_PRINCIPAL,
    deviceId = null,
    minSnapshotVersion = 0,
    headSnapshotVersion = 0,
    snapshotBrokerToSequence = () => null,
    maxAgeMs = Infinity,
    now = Date.now,
  },
) {
  let token;
  try {
    token = decodeCursor(rawCursor, secret);
  } catch {
    return { ok: false, status: 400, code: 'CURSOR_INVALID' };
  }

  if (token.v !== CURSOR_VERSION) {
    return { ok: false, status: 400, code: 'CURSOR_VERSION_UNSUPPORTED' };
  }
  if (token.serverInstanceId !== serverInstanceId) {
    return { ok: false, status: 410, code: 'CURSOR_SERVER_CHANGED' };
  }
  if (token.journalEpoch !== journalEpoch) {
    return { ok: false, status: 410, code: 'CURSOR_EPOCH_EXPIRED' };
  }
  if (token.schemaVersion !== schemaVersion) {
    return { ok: false, status: 410, code: 'CURSOR_SCHEMA_CHANGED' };
  }
  if (token.deltaVersion !== deltaVersion) {
    return { ok: false, status: 410, code: 'CURSOR_DELTA_UNSUPPORTED' };
  }
  // 不泄露另一个 principal 的 cursor 是否有效。
  if (token.principal !== principal) {
    return { ok: false, status: 403, code: 'FORBIDDEN' };
  }
  if (token.deviceId != null && deviceId != null && token.deviceId !== deviceId) {
    return { ok: false, status: 403, code: 'FORBIDDEN' };
  }
  if (Number.isFinite(maxAgeMs) && token.issuedAt !== undefined) {
    const age = now() - token.issuedAt;
    if (age > maxAgeMs) {
      return { ok: false, status: 410, code: 'CURSOR_AGE_EXPIRED' };
    }
  }
  if (token.snapshotVersion < minSnapshotVersion) {
    return { ok: false, status: 410, code: 'CURSOR_EXPIRED' };
  }
  if (token.snapshotVersion > headSnapshotVersion) {
    return { ok: false, status: 410, code: 'CURSOR_AHEAD_OF_SERVER' };
  }
  const snapshotBrokerTo = snapshotBrokerToSequence(token.snapshotVersion);
  if (snapshotBrokerTo != null && token.brokerToSequence !== snapshotBrokerTo) {
    return { ok: false, status: 410, code: 'CURSOR_COVERAGE_MISMATCH' };
  }
  return { ok: true, token };
}

/**
 * cursor 签名密钥:TPLANNER_CURSOR_SECRET 显式覆盖;否则用 dbPath 旁的一次性
 * 随机密钥文件(dbPath.cursor-secret),保证同部署跨重启稳定。密钥文件不是
 * 认证凭据 —— 认证必须由外层的 authenticated principal 完成(§11)。
 */
export function resolveCursorSecret(dbPath, envSecret = process.env.TPLANNER_CURSOR_SECRET) {
  if (envSecret && envSecret !== '') return envSecret;
  const secretPath = `${dbPath}.cursor-secret`;
  try {
    const existing = readFileSync(secretPath, 'utf8').trim();
    if (existing.length >= 32) return existing;
  } catch {
    // 文件不存在:下面生成。
  }
  const secret = randomBytes(32).toString('hex');
  mkdirSync(dirname(secretPath), { recursive: true });
  writeFileSync(secretPath, `${secret}\n`, { mode: 0o600 });
  return secret;
}
