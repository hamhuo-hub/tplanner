// Fastify 应用工厂:publisher / validateBatch / store 由调用方注入,便于 inject 测试。
// 端点见 docs/sync-v3.md §11。
import Fastify from 'fastify';

export function buildServer({ publisher, validateBatch, store, health, legacy }) {
  const app = Fastify({ logger: false });

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

  // 能力探测:客户端据此判断协议/schema 兼容与批次上限(§11)
  app.get('/tplanner/v3/capabilities', async () => store.capabilities());

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

  // 最新快照:no-store,下载的是 gzip 信封;ETag 条件请求返回 304(§11/§22)
  app.get('/tplanner/v3/snapshots/latest', async (request, reply) => {
    const meta = store.latestSnapshotMeta();
    if (!meta) return reply.code(404).send({ error: 'SNAPSHOT_NOT_FOUND' });
    if (isNotModified(request, meta.compressedHash)) {
      return snapshotHeaders(reply, meta, { immutable: false }).code(304).send();
    }
    return snapshotHeaders(reply, meta, { immutable: false })
      .header('Content-Type', 'application/octet-stream')
      .header('Content-Encoding', 'gzip')
      .send(store.snapshotPayload(meta.version));
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
      .header('Content-Type', 'application/octet-stream')
      .header('Content-Encoding', 'gzip')
      .send(store.snapshotPayload(version));
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
  app.get('/health/ready', async (request, reply) => {
    const r = await health.readiness();
    return reply.code(r.ok ? 200 : 503).send(r);
  });

  // 运行指标(§15)
  app.get('/tplanner/v3/status', async () => health.status());

  // ── V1 兼容路由(§21,过渡期专用;legacy 未注入则整个不注册)──
  if (legacy) {
    const legacyPut = (fn) => async (request, reply) => {
      if (legacy.writesDisabled) {
        return reply.code(410).send({ error: 'V1_WRITES_DISABLED' });
      }
      try {
        return await fn(request);
      } catch (err) {
        if (err?.code === 'V1_WRITES_DISABLED') {
          return reply.code(410).send({ error: 'V1_WRITES_DISABLED' });
        }
        throw err;
      }
    };

    app.get('/tplanner/events', async () => legacy.getEvents());
    app.put('/tplanner/events', legacyPut((request) => legacy.putEvents(request.body)));
    app.get('/tplanner/journals', async () => legacy.getJournals());
    app.put('/tplanner/journals', legacyPut((request) => legacy.putJournals(request.body)));
    app.get('/tplanner/goals', async () => legacy.getGoals());
    app.put('/tplanner/goals', legacyPut((request) => legacy.putGoals(request.body)));
    app.get('/tplanner/insights', async () => legacy.getInsights());
    app.put('/tplanner/insights', legacyPut((request) => legacy.putInsights(request.body)));
    app.get('/tplanner/changes', async (request, reply) => {
      const since = Number(request.query.since ?? 0);
      if (!Number.isInteger(since) || since < 0) {
        return reply.code(400).send({ error: 'BAD_SINCE' });
      }
      return legacy.changes({ since });
    });
    app.get('/tplanner/time', async () => legacy.serverTime());
    // V1 健康检查别名(旧客户端与外部探针依赖 /health)
    app.get('/health', async () => ({ status: 'ok' }));
  }

  return app;
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
