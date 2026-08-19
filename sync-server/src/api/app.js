// Fastify 应用工厂:publisher 与 validateBatch 由调用方注入,便于 inject 测试。
import Fastify from 'fastify';

export function buildServer({ publisher, validateBatch }) {
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

  return app;
}
