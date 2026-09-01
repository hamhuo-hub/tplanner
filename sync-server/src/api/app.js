// Fastify 应用工厂:publisher / validateBatch / store 由调用方注入,便于 inject 测试。
// 端点见 docs/sync-v3.md §11。
import Fastify from 'fastify';
import cors from '@fastify/cors';

const DEFAULT_NOTIFICATION_WAIT_MS = 25_000;
const MAX_NOTIFICATION_WAIT_MS = 30_000;
const CORS_METHODS = ['GET', 'POST', 'OPTIONS'];
const CORS_ALLOWED_HEADERS = [
  'Content-Type',
  'Authorization',
  'Idempotency-Key',
  'Cache-Control',
  'If-None-Match',
];
const CORS_EXPOSED_HEADERS = ['ETag', 'X-Snapshot-Version', 'X-State-Hash'];

export function buildServer({ publisher, validateBatch, store, health, logger = false, changes = null }) {
  const app = Fastify({ logger });

  app.register(cors, {
    origin(origin, callback) {
      callback(null, isAllowedOrigin(origin));
    },
    methods: CORS_METHODS,
    allowedHeaders: CORS_ALLOWED_HEADERS,
    exposedHeaders: CORS_EXPOSED_HEADERS,
    maxAge: 86_400,
  });

  // 命令批次入口。BROKER_PERSISTED 只表示"broker 已持久接收",不是全端同步成功。
  app.post('/tplanner/v3/command-batches', async (request, reply) => {
    const body = request.body;

    const problems = validateBatch(body);
    if (problems) {
      return reply.code(400).send({ error: 'SCHEMA_UNSUPPORTED', details: problems });
    }

    const idempotencyKey = request.headers['idempotency-key'];
    if (typeof idempotencyKey !== 'string' || idempotencyKey !== body.batchId) {
      return reply.code(400).send({ error: 'IDEMPOTENCY_KEY_MISMATCH' });
    }

    try {
      const result = await publisher.publish(body);
      return reply.code(202).send(result);
    } catch (err) {
      request.log.error({ err }, 'broker publish failed');
      return reply.code(503).send({ error: 'BROKER_UNAVAILABLE' });
    }
  });

  // 能力探测:客户端据此判断协议/schema 兼容、下行模式与批次上限(§11)。
  // delta 未启用或未挂载 changes 服务时,downlinkModes 只有 snapshot —— 一键回滚。
  app.get('/tplanner/v3/capabilities', async () => {
    const base = store.capabilities();
    const delta = changes?.capabilities();
    if (!delta) return { ...base, downlinkModes: ['snapshot'] };
    return { ...base, downlinkModes: delta.downlinkModes, delta: delta.delta };
  });

  // 增量下行(delta-v1,§9.3/§9.4):cursor 校验、按 commit 分页、410 → full snapshot
  app.get('/tplanner/v3/changes', async (request, reply) => {
    if (!changes) {
      return reply.code(410).send({
        error: 'DELTA_DISABLED',
        recovery: 'FULL_SNAPSHOT',
        latestSnapshotVersion: store.latestSnapshotMeta()?.version ?? 0,
      });
    }
    const maxCommits = request.query.maxCommits === undefined
      ? undefined
      : Number(request.query.maxCommits);
    const result = changes.handleChanges(request.query.cursor, maxCommits);
    return reply.code(result.status).send(result.body);
  });

  // 回执:deviceId 自报身份(query),afterClientSequence 为游标(§11)
  app.get('/tplanner/v3/receipts', async (request, reply) => {
    const deviceId = request.query.deviceId;
    if (typeof deviceId !== 'string' || deviceId === '') {
      return reply.code(400).send({ error: 'DEVICE_ID_REQUIRED' });
    }
    const after = Number(request.query.afterClientSequence ?? 0);
    if (!Number.isInteger(after) || after < 0) {
      return reply.code(400).send({ error: 'BAD_AFTER_CLIENT_SEQUENCE' });
    }
    return {
      acceptedThrough: store.acceptedThrough(deviceId),
      results: store.receiptsForDevice(deviceId, after),
    };
  });

  // 最新快照:no-store,只返回轻量 manifest;载荷由版本端点下载(§11/§22)
  app.get('/tplanner/v3/snapshots/latest', async (request, reply) => {
    const meta = store.latestSnapshotMeta();
    if (!meta) return reply.code(404).send({ error: 'SNAPSHOT_NOT_FOUND' });
    if (isNotModified(request, meta.compressedHash)) {
      return snapshotHeaders(reply, meta, { immutable: false }).code(304).send();
    }
    return snapshotHeaders(reply, meta, { immutable: false })
      .send(snapshotManifest(meta));
  });

  // 指定版本快照:immutable + ETag = compressedHash,支持 304(§11/§22)
  app.get('/tplanner/v3/snapshots/:version', async (request, reply) => {
    const version = Number(request.params.version);
    if (!Number.isInteger(version) || version < 1) {
      return reply.code(400).send({ error: 'BAD_SNAPSHOT_VERSION' });
    }
    const meta = store.snapshotMeta(version);
    if (!meta) return reply.code(404).send({ error: 'SNAPSHOT_NOT_FOUND' });
    if (isNotModified(request, meta.compressedHash)) {
      return snapshotHeaders(reply, meta, { immutable: true }).code(304).send();
    }
    return snapshotHeaders(reply, meta, { immutable: true })
      // This is a gzip *file*, not HTTP content coding. Setting
      // Content-Encoding would make browsers transparently decompress it and
      // invalidate compressedHash verification in the client.
      .header('Content-Type', 'application/gzip')
      .send(store.snapshotPayload(version));
  });

  // 快照版本通知:若已有更新立即返回,否则等待 SQLite latest_snapshot 变化或超时。
  app.get('/tplanner/v3/notifications', async (request, reply) => {
    const afterVersion = parseNonNegativeInteger(request.query.afterVersion, 0);
    if (afterVersion === null) {
      return reply.code(400).send({ error: 'BAD_AFTER_VERSION' });
    }
    const waitMs = parseNonNegativeInteger(request.query.wait, DEFAULT_NOTIFICATION_WAIT_MS);
    if (waitMs === null || waitMs > MAX_NOTIFICATION_WAIT_MS) {
      return reply.code(400).send({ error: 'BAD_WAIT' });
    }

    const meta = await store.waitForLatestSnapshot(afterVersion, { waitMs });
    return {
      latestVersion: meta?.version ?? 0,
      stateHash: meta?.stateHash ?? null,
    };
  });

  // 设备快照安装 ACK:更新 device_progress(§11)
  app.post('/tplanner/v3/devices/:deviceId/snapshot-acks', async (request, reply) => {
    const { deviceId } = request.params;
    const { version, stateHash } = request.body ?? {};
    if (!Number.isInteger(version) || version < 1 || typeof stateHash !== 'string' || stateHash === '') {
      return reply.code(400).send({ error: 'SCHEMA_UNSUPPORTED' });
    }
    store.recordSnapshotAck(deviceId, { version, stateHash });
    return reply.code(202).send({ deviceId, version, state: 'ACK_RECORDED' });
  });

  // 存活与就绪(§15)
  app.get('/health/live', async () => ({ status: 'alive' }));
  const readiness = async (request, reply) => {
    const r = await health.readiness();
    return reply.code(r.ok ? 200 : 503).send(r);
  };
  app.get('/health/ready', readiness);
  app.get('/health', readiness);

  // 运行指标(§15)
  app.get('/tplanner/v3/status', async () => health.status());

  return app;
}

function isAllowedOrigin(origin) {
  if (origin === undefined || origin === 'null' || origin === 'file://') return true;
  if (origin === 'https://plan.hamhuo.top') return true;
  return /^https?:\/\/(?:localhost|127\.0\.0\.1)(?::\d{1,5})?$/.test(origin);
}

function parseNonNegativeInteger(value, fallback) {
  if (value === undefined) return fallback;
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function snapshotManifest(meta) {
  return {
    snapshotVersion: meta.version,
    parentVersion: meta.parentVersion,
    schemaVersion: meta.schemaVersion,
    stateHash: meta.stateHash,
    compressedHash: meta.compressedHash,
    encoding: 'gzip',
    compressedBytes: meta.compressedBytes,
    uncompressedBytes: meta.uncompressedBytes,
    serverInstanceId: meta.serverInstanceId,
  };
}

function isNotModified(request, compressedHash) {
  return request.headers['if-none-match'] === `"${compressedHash}"`;
}

function snapshotHeaders(reply, meta, { immutable }) {
  return reply
    .header('ETag', `"${meta.compressedHash}"`)
    .header('X-Snapshot-Version', String(meta.version))
    .header('X-State-Hash', meta.stateHash)
    .header('Cache-Control', immutable ? 'private, max-age=31536000, immutable' : 'no-store');
}
