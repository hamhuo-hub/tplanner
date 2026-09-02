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
import {
  createMaterializer,
  ensureBootstrapSnapshot,
  ensureReceiptCoverageSnapshot,
} from './materializer.js';
import { applyCommand } from './reducer.js';
import { createNatsConnection } from '../broker/natsConnection.js';
import { ensureStreams } from '../broker/streams.js';
import { resolveServerInstanceId } from '../serverInstance.js';
import { SEQ_MULTIPLIER } from '../sequence.js';
import {
  JournalValidationError,
  validateJournalHead,
  validateJournalTail,
} from '../state/journalValidator.js';

const STREAM_COMMANDS = 'TPLANNER_COMMANDS';
const CONSUMER_NAME = 'state-builder';
const SUBJECT_COMMANDS = 'tplanner.v3.commands';

// Shadow validator 节拍(§9.1):启动时从 min_snapshot_version 全链验证一次,
// 之后在主循环迭代边界检查该间隔(约每 60s,与流量无关),只验新增尾部,
// 只在 journal 本身失配时 fail closed,绝不修复。
const JOURNAL_VALIDATION_INTERVAL_MS = 60_000;

// integration batch 边界(§6)。nats.js v3 要求 pull expires >= 1000ms，
// 但业务静默窗口不应因此被放大：reader 保留尚未完成的 pull，并用本地
// 100ms timer 决定当前批次边界；迟到的 pull 结果会成为下一批的首条消息，
// 不会丢失、越序或产生第二个并发 pull。
export const LIMITS = {
  quietMs: 100, // 距最后一条消息安静 100ms 结束
  forcedMs: 5000, // 自首条起 5s 强制结束(兜底)
  maxCommands: 100,
  maxBytes: 256 * 1024,
};

const MIN_PULL_EXPIRES_MS = 1000;

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 在 nats.js 的 1s pull 下限之上提供本地静默窗口。
 *
 * 同一时刻最多只有一个 consumer.next 在途。quiet/forced timer 先到时，
 * pending pull 被保留下来并在下一次 nextBatch 复用；因此已经被 broker
 * 投递、但未进入当前批次的消息不会被遗忘。
 */
export function createIntegrationBatchReader(
  consumer,
  {
    limits = LIMITS,
    now = () => Date.now(),
    wait = delay,
  } = {},
) {
  let pendingPull = null;
  let bufferedMessage = null;

  const beginPull = (expires) => {
    if (!pendingPull) {
      pendingPull = consumer.next({ expires: Math.max(MIN_PULL_EXPIRES_MS, expires) }).then(
        (value) => ({ value }),
        (error) => ({ error }),
      );
    }
    return pendingPull;
  };

  const finishPull = async (expires) => {
    if (bufferedMessage) {
      const value = bufferedMessage;
      bufferedMessage = null;
      return value;
    }
    const pull = beginPull(expires);
    const result = await pull;
    if (pendingPull === pull) pendingPull = null;
    if (result.error) throw result.error;
    return result.value;
  };

  async function nextBatch() {
    const first = await finishPull(60_000);
    if (!first) return null;

    const batchStartMs = now();
    const messages = [first];
    let entries = expandMessage(first);
    let bytes = first.data.length;

    while (entries.length < limits.maxCommands && bytes < limits.maxBytes) {
      const forcedRemainingMs = limits.forcedMs - (now() - batchStartMs);
      if (forcedRemainingMs <= 0) break;

      const pull = beginPull(MIN_PULL_EXPIRES_MS);
      const boundaryMs = Math.min(limits.quietMs, forcedRemainingMs);
      const outcome = await Promise.race([
        pull.then((result) => ({ kind: 'pull', result })),
        wait(boundaryMs).then(() => ({ kind: 'boundary' })),
      ]);

      if (outcome.kind === 'boundary') {
        // 保留 pendingPull；下一批会先消费同一个结果。
        break;
      }

      if (pendingPull === pull) pendingPull = null;
      if (outcome.result.error) throw outcome.result.error;
      const next = outcome.result.value;
      if (!next) break;

      const nextEntries = expandMessage(next);
      // NATS ACKs the whole uploaded message, so it cannot be split across
      // integration batches. Keep an already-pulled message as the exact first
      // message of the next batch when adding it would cross either hard cap.
      if (
        entries.length + nextEntries.length > limits.maxCommands
        || bytes + next.data.length > limits.maxBytes
      ) {
        bufferedMessage = next;
        break;
      }

      messages.push(next);
      entries = [...entries, ...nextEntries];
      bytes += next.data.length;
    }

    return { messages, entries };
  }

  return { nextBatch };
}

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

  let nc = null;
  let closed = false;
  let leaseLossError = null;
  let renewTimer = null;
  const stop = () => {
    closed = true;
    nc?.close().catch(() => {});
  };
  const assertLease = (stage) => {
    if (leaseLossError || !lease.renewLease(ownerId)) {
      leaseLossError ??= new Error(`state builder lease lost during ${stage}`);
      stop();
      throw leaseLossError;
    }
  };
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);

  // Start renewal immediately after acquisition. NATS setup, startup snapshot recovery and
  // publication replay are part of the single-writer critical section too; delaying this timer
  // until after them allowed a slow startup to outlive the original lease.
  renewTimer = setInterval(() => {
    if (closed) return;
    if (!lease.renewLease(ownerId)) {
      leaseLossError = new Error('state builder lease renewal failed; another builder took over');
      log.error(leaseLossError.message);
      stop();
    }
  }, Math.max(1_000, Math.floor(leaseTtlMs / 3)));
  renewTimer.unref?.();

  try {
    nc = await createNatsConnection({ credsFile: process.env.NATS_CREDS_FILE });
    assertLease('NATS connection');
    const jsm = await jetstreamManager(nc);
    const js = await ensureStreams(nc);
    assertLease('stream initialization');

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
    const batchReader = createIntegrationBatchReader(consumer);
    assertLease('consumer attachment');

    const bootstrap = ensureBootstrapSnapshot(db, {
      serverInstanceId: instanceId,
      assertWriterLease: () => assertLease('bootstrap snapshot transaction'),
    });
    if (bootstrap) log.info({ snapshotVersion: bootstrap.snapshotVersion }, 'empty bootstrap snapshot committed');
    assertLease('bootstrap snapshot recovery');
    const coverage = ensureReceiptCoverageSnapshot(db, {
      serverInstanceId: instanceId,
      assertWriterLease: () => assertLease('receipt coverage transaction'),
    });
    if (coverage) {
      log.info(
        {
          snapshotVersion: coverage.manifest.snapshotVersion,
          brokerToSequence: coverage.envelope.brokerToSequence,
        },
        'recovered terminal receipt coverage snapshot',
      );
    }
    assertLease('receipt coverage recovery');

    // Shadow reconstruction validator(§9.1):只证明 journal 可信,绝不修复。
    // 启动先全链验证;失配按 P0 记日志,不停止 builder(snapshot 路径不受影响)。
    let journalCheckpoint = null;
    let lastJournalValidationAt = 0;
    const runJournalValidation = (label) => {
      try {
        if (journalCheckpoint === null) {
          journalCheckpoint = validateJournalHead(db);
        } else {
          const head = db
            .prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1')
            .get()?.version ?? 0;
          journalCheckpoint = validateJournalTail(db, {
            fromSnapshotVersion: journalCheckpoint.toSnapshotVersion,
            toSnapshotVersion: head,
            baseState: journalCheckpoint.headState,
          });
        }
        if (journalCheckpoint.validatedCommits > 0) {
          log.info(
            {
              validatedCommits: journalCheckpoint.validatedCommits,
              toSnapshotVersion: journalCheckpoint.toSnapshotVersion,
            },
            `journal shadow validation passed (${label})`,
          );
        }
      } catch (err) {
        journalCheckpoint = null; // 下个节拍回到全链验证
        if (err instanceof JournalValidationError) {
          // P0 correctness alert:journal 与 immutable snapshot 失配。
          log.error(
            { code: err.code, message: err.message },
            `journal shadow validation FAILED (${label})`,
          );
        } else {
          log.error({ err }, `journal shadow validation crashed (${label})`);
        }
      }
    };
    runJournalValidation('startup');

    const materializer = createMaterializer({
      db,
      applyCommand,
      serverInstanceId: instanceId,
      assertWriterLease: () => assertLease('materializer transaction'),
    });
    const outbox = createOutboxDriver(db, {
      publish: async (_type, payload, dedupeKey) => {
        await js.publish(SUBJECT_SNAPSHOT_READY, JSON.stringify(payload), { msgID: dedupeKey });
      },
    });

    // 3. 补发崩溃遗留的发布
    const recovered = await outbox.flush();
    if (recovered.length) log.info({ recovered }, 'recovered pending publications');
    assertLease('publication recovery');

    // 4. 主循环
    while (!closed) {
      // Shadow validator 的独立节拍:每次循环迭代边界(繁忙时每批之间、空闲时
      // 每个 60s 拉取超时之后)都会检查,与流量大小无关 —— 不是挂在"无批"分支上。
      if (Date.now() - lastJournalValidationAt >= JOURNAL_VALIDATION_INTERVAL_MS) {
        lastJournalValidationAt = Date.now();
        runJournalValidation('interval');
      }
      let batch;
      try {
        batch = await batchReader.nextBatch();
      } catch (err) {
        if (closed) break;
        throw err;
      }
      if (!batch) continue;
      const { messages, entries } = batch;

      try {
        assertLease('integration batch');
        const { snapshot } = materializer.processIntegrationBatch(entries);
        assertLease('integration commit');
        await outbox.flush();
        assertLease('publication flush');
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
    if (leaseLossError) throw leaseLossError;
  } finally {
    if (renewTimer) clearInterval(renewTimer);
    process.off('SIGINT', stop);
    process.off('SIGTERM', stop);
    if (!closed) await nc?.close().catch(() => {});
    lease.releaseLease(ownerId);
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
