// 负载压测:直接驱动 materializer(不经 NATS),可跑 1k/10k/100k 命令,
// 在树莓派上验证 §25 的资源目标。用法:
//   node scripts/load-harness.mjs [N=10000] [batchSize=100] [--file db.sqlite]
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer } from '../src/materializer/materializer.js';
import { applyCommand } from '../src/materializer/reducer.js';

const TYPES = ['task.setTitle', 'task.setCompleted', 'task.setNote', 'task.setSchedule'];

export function generateEntries(n, { startBrokerSeq = 1, startClientSeq = 1 } = {}) {
  const entries = [];
  for (let i = 0; i < n; i++) {
    const create = i % 20 === 0;
    const type = create ? 'task.create' : TYPES[i % TYPES.length];
    const aggregateId = `task-${(i % 1000) + 1}`; // 1000 个任务轮转,制造高频同实体编辑
    const arguments_ = create
      ? { title: `t${i}` }
      : type === 'task.setCompleted'
        ? { completed: i % 2 === 0 }
        : type === 'task.setSchedule'
          ? { schedule: { startAt: '2026-08-20T01:00:00.000Z', endAt: null } }
          : type === 'task.setNote'
            ? { note: `n${i}` }
            : { title: `t${i}` };
    entries.push({
      brokerSequence: startBrokerSeq + i,
      deviceId: 'load-dev',
      batchId: 'load',
      command: { commandId: randomUUID(), clientSequence: startClientSeq + i, type, aggregateId, arguments: arguments_ },
    });
  }
  return entries;
}

export function runLoad({ n, batchSize, dbPath = ':memory:' }) {
  const db = openDatabase(dbPath);
  const m = createMaterializer({ db, applyCommand, serverInstanceId: 'srv-load' });
  const entries = generateEntries(n);

  const t0 = performance.now();
  let snapshots = 0;
  let lastStateHash = null;
  for (let i = 0; i < entries.length; i += batchSize) {
    const result = m.processIntegrationBatch(entries.slice(i, i + batchSize));
    if (result.snapshot) {
      snapshots += 1;
      lastStateHash = result.snapshot.manifest.stateHash;
    }
  }
  const ms = performance.now() - t0;

  const entities = db.prepare('SELECT COUNT(*) AS c FROM entities').get().c;
  const receipts = db.prepare('SELECT COUNT(*) AS c FROM processed_commands').get().c;
  db.close();

  return {
    n,
    batchSize,
    ms: Math.round(ms),
    commandsPerSec: Math.round((n / ms) * 1000),
    snapshots,
    entities,
    receipts,
    stateHash: lastStateHash,
  };
}

const isMain = process.argv[1] && process.argv[1].endsWith('load-harness.mjs');
if (isMain) {
  const args = process.argv.slice(2);
  const n = Number(args.find((a) => /^\d+$/.test(a)) ?? 10_000);
  const batchSize = Number(args.slice(1).find((a) => /^\d+$/.test(a)) ?? 100);
  const fileIdx = args.indexOf('--file');
  const dbPath = fileIdx >= 0 ? args[fileIdx + 1] : ':memory:';

  const result = runLoad({ n, batchSize, dbPath });
  console.log(JSON.stringify(result, null, 2));
}
