// State Builder 入口(见 docs/sync-v3.md §6/§9/§10/§15)。
//
// 启动序列:
//   1. 抢写者租约(BEGIN IMMEDIATE)——失败即退出,绝不允许第二个消费者;
//   2. 确保流存在 + attach durable consumer(租约在手才 attach);
//   3. 补发上次崩溃遗留的 publication_outbox(§10 快照提交后、ready 前);
//   4. 主循环:按 §6 边界收 integration batch → materializer 单事务整合
//      → flush 发布 → ACK。整合失败不 ACK,等 JetStream 重投(命令幂等)。
//
// 每条命令的 broker_sequence 从批次 MQ 序列派生:seq * 1_000_000 + 批内序号,
// 保证全局唯一、有序、重投确定(§9 processed_commands.broker_sequence UNIQUE)。
import { randomUUID } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { jetstreamManager } from '@nats-io/jetstream';
import { openDatabase } from '../state/database.js';
import { createLease } from './lease.js';
import { createOutboxDriver, SUBJECT_SNAPSHOT_READY } from './outbox.js';
import { createMaterializer } from './materializer.js';
import { applyCommand } from './reducer.js';
import { createNatsConnection } from '../broker/natsConnection.js';
import { ensureStreams } from '../broker/streams.js';
import { resolveServerInstanceId } from '../serverInstance.js';
import { SEQ_MULTIPLIER } from '../sequence.js';

const STREAM_COMMANDS = 'TPLANNER_COMMANDS';
const CONSUMER_NAME = 'state-builder';
const SUBJECT_COMMANDS = 'tplanner.v3.commands';

// integration batch 边界(§6):nats.js v3 的 pull expires 下限 1000ms,
// 静默窗口因此取 1000ms(单命令场景快照延迟 ≈1s);强制上限防长流无限合并。
const LIMITS = {
  quietMs: 1000, // 距最后一条消息安静 1000ms 结束
  forcedMs: 5000, // 自首条起 5s 强制结束(兜底)
  maxCommands: 100,
  maxBytes: 256 * 1024,
};

export function expandMessage(msg) {
  const batch = JSON.parse(Buffer.from(msg.data).toString('utf8'));
  return (batch.commands ?? []).map((command, index) => ({
    brokerSequence: msg.seq * SEQ_MULTIPLIER + index,
    deviceId: batch.deviceId,
    batchId: batch.batchId,
    command,
  }));
}

export async function startStateBuilder({
  dbPath = process.env.TPLANNER_DB_PATH || '/var/lib/tplanner-sync/state/tplanner.db',
  serverInstanceId,
  leaseTtlMs = 30_000,
  log = console,
} = {}) {
  const db = openDatabase(dbPath);
  const instanceId = serverInstanceId ?? resolveServerInstanceId(dbPath);

  // 1. 写者租约:失败即抛,绝不带着第二个消费者启动
  const ownerId = `builder-${randomUUID()}`;
  const lease = createLease(db, { ttlMs: leaseTtlMs });
  const acquired = lease.acquire(ownerId);
  if (!acquired.acquired) {
    throw new Error(
      `state builder lease held by ${acquired.currentOwner} until ${acquired.leaseExpiresAt}`,
    );
  }
  log.info({ ownerId }, 'state builder lease acquired');

  const nc = await createNatsConnection({ credsFile: process.env.NATS_CREDS_FILE });
  let closed = false;
  const stop = () => {
    closed = true;
    nc.close().catch(() => {});
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);

  try {
    const jsm = await jetstreamManager(nc);
    const js = await ensureStreams(nc);

    // 2. durable consumer:显式 ACK,60s 重投窗口,永不进死信(§5:失败必须停下来重试)
    try {
      await jsm.consumers.info(STREAM_COMMANDS, CONSUMER_NAME);
    } catch {
      await jsm.consumers.add(STREAM_COMMANDS, {
        durable_name: CONSUMER_NAME,
        ack_policy: 'explicit',
        ack_wait: 60_000_000_000, // 60s
        max_deliver: -1,
        filter_subject: SUBJECT_COMMANDS,
      });
    }
    const consumer = await js.consumers.get(STREAM_COMMANDS, CONSUMER_NAME);

    const materializer = createMaterializer({ db, applyCommand, serverInstanceId: instanceId });
    const outbox = createOutboxDriver(db, {
      publish: async (_type, payload, dedupeKey) => {
        await js.publish(SUBJECT_SNAPSHOT_READY, JSON.stringify(payload), { msgID: dedupeKey });
      },
    });

    // 3. 补发崩溃遗留的发布
    const recovered = await outbox.flush();
    if (recovered.length) log.info({ recovered }, 'recovered pending publications');

    // 4. 租约续期:丢失即退出(systemd 会重启本单元)
    const renewTimer = setInterval(() => {
      if (!lease.renewLease(ownerId)) {
        log.error('state builder lease renewal failed; another builder took over');
        stop();
        process.exit(1);
      }
    }, Math.max(1_000, Math.floor(leaseTtlMs / 3)));
    renewTimer.unref?.();

    // 5. 主循环
    while (!closed) {
      let first;
      try {
        first = await consumer.next({ expires: 60_000 }); // 空闲长轮询
      } catch (err) {
        if (closed) break;
        throw err;
      }
      if (!first) continue;

      const batchStartMs = Date.now();
      const messages = [first];
      let entries = expandMessage(first);
      let bytes = first.data.length;

      while (
        entries.length < LIMITS.maxCommands &&
        bytes < LIMITS.maxBytes &&
        Date.now() - batchStartMs < LIMITS.forcedMs
      ) {
        const next = await consumer.next({ expires: LIMITS.quietMs });
        if (!next) break;
        messages.push(next);
        entries = [...entries, ...expandMessage(next)];
        bytes += next.data.length;
      }

      try {
        const { snapshot } = materializer.processIntegrationBatch(entries);
        await outbox.flush();
        for (const msg of messages) msg.ack();
        if (snapshot) {
          log.info(
            {
              snapshotVersion: snapshot.manifest.snapshotVersion,
              commands: entries.length,
              brokerFrom: messages[0].seq,
              brokerTo: messages[messages.length - 1].seq,
            },
            'integration batch committed',
          );
        }
      } catch (err) {
        // 不 ACK:JetStream 按 ack_wait 重投;命令幂等,重放安全(§5/§10)
        log.error({ err, seqs: messages.map((m) => m.seq) }, 'integration failed; waiting for redelivery');
        await new Promise((resolve) => setTimeout(resolve, 1_000));
      }
    }
  } finally {
    if (!closed) await nc.close().catch(() => {});
    db.close();
    log.info('state builder stopped');
  }
}

// 直接执行:包内 start:builder = node src/materializer/main.js
const isMain =
  process.argv[1] &&
  fileURLToPath(import.meta.url) === fileURLToPath(pathToFileURL(process.argv[1]));
if (isMain) {
  startStateBuilder().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
