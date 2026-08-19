// Fastify 应用工厂:publisher / validateBatch / store 由调用方注入,便于 inject 测试。
// 端点见 docs/sync-v3.md §11。
import Fastify from 'fastify';

export function buildServer({ publisher, validateBatch, store }) {
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

  // 最新快照:no-store,下载的是 gzip 信封(§11)
  app.get('/tplanner/v3/snapshots/latest', async (request, reply) => {
    const snap = store.latestSnapshot();
    if (!snap) return reply.code(404).send({ error: 'SNAPSHOT_NOT_FOUND' });
    return sendSnapshot(reply, snap, { immutable: false });
  });

  // 指定版本快照:immutable + ETag = compressedHash(§11)
  app.get('/tplanner/v3/snapshots/:version', async (request, reply) => {
    const version = Number(request.params.version);
    if (!Number.isInteger(version) || version < 1) {
      return reply.code(400).send({ error: 'BAD_SNAPSHOT_VERSION' });
    }
    const snap = store.snapshotByVersion(version);
    if (!snap) return reply.code(404).send({ error: 'SNAPSHOT_NOT_FOUND' });
    return sendSnapshot(reply, snap, { immutable: true });
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

  return app;
}

function sendSnapshot(reply, snap, { immutable }) {
  return reply
    .header('Content-Type', 'application/octet-stream')
    .header('Content-Encoding', 'gzip')
    .header('ETag', `"${snap.compressedHash}"`)
    .header('X-Snapshot-Version', String(snap.version))
    .header('X-State-Hash', snap.stateHash)
    .header('Cache-Control', immutable ? 'private, max-age=31536000, immutable' : 'no-store')
    .send(Buffer.from(snap.compressedPayload));
}
