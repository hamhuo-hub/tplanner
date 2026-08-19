// V1 兼容层(见 docs/sync-v3.md §21)。过渡期专用,不可长期保留:
//
// 下行:从 entities 表投影旧版形状(events 数组 / journals 映射 / goals 数组 /
//   insights {entries, reports})。V3 状态里只有 broker 序列没有墙钟时间,
//   投影用 updated_at(最后写入的真实毫秒,含删除那次)承担旧格式的
//   updatedAt / deletedAt,墓碑得以传播,旧客户端 30 天 TTL 语义不复活已删实体。
//
// 上行:PUT 先与当前权威态 diff,**只把真正变化的字段转成细粒度命令**,
//   禁止旧客户端整库覆盖(评审结论)。所有 legacy 客户端共用 legacy-compat
//   设备身份,进程内串行分配 clientSequence(材料器要求严格连续)。
//
// 已知过渡期限制(不修,升级后消失):journal/goal 无 restore 命令,
// 旧客户端对已删 journal/goal 的"复活写"会被中央拒绝为 ENTITY_DELETED;
// checklist 级变更不映射。
import { randomUUID } from 'node:crypto';
import { loadStateFromDb } from '../materializer/materializer.js';
import { acceptedThrough, findReceipt } from '../state/receipts.js';

export const LEGACY_DEVICE_ID = 'legacy-compat';

// ── 下行投影 ─────────────────────────────────────────────────────────────

export function projectLegacy(db) {
  const rows = db
    .prepare('SELECT entity_type, entity_id, lifecycle, payload_json, updated_at FROM entities')
    .all();

  const events = [];
  const journals = {};
  const goals = [];
  const entries = [];
  const reports = {};

  for (const row of rows) {
    const payload = JSON.parse(row.payload_json);
    const deleted = row.lifecycle === 'deleted';
    const updatedAt = row.updated_at;
    const deletedAt = deleted ? updatedAt : null;

    switch (row.entity_type) {
      case 'task':
        events.push({ id: row.entity_id, payload, updatedAt, deletedAt });
        break;
      case 'journal':
        journals[row.entity_id] = { text: payload.text ?? '', updatedAt, deletedAt };
        break;
      case 'goal':
        goals.push({ id: row.entity_id, payload, updatedAt, deletedAt });
        break;
      case 'insight':
        if (row.entity_id.startsWith('report-')) {
          const date = row.entity_id.slice('report-'.length);
          reports[date] = { ...payload, date };
        } else {
          entries.push({ id: row.entity_id, ...payload });
        }
        break;
      default:
        break;
    }
  }

  return { events, journals, goals, insights: { entries, reports } };
}

// ── 上行 diff → 命令 ──────────────────────────────────────────────────────

const make = (type, aggregateId, args) => ({ type, aggregateId, arguments: args ?? {} });

export function diffEventsToCommands(state, incoming) {
  const commands = [];
  for (const e of Array.isArray(incoming) ? incoming : []) {
    const id = e?.id;
    if (id == null || id === '') continue;
    const cur = state.tasks[id];
    const p = e.payload ?? {};
    const deleted = Boolean(e.deletedAt);

    if (!cur) {
      commands.push(make('task.create', id, {
        title: typeof p.title === 'string' ? p.title : '',
        itemType: typeof p.itemType === 'string' ? p.itemType : 'task',
      }));
      if (typeof p.note === 'string' && p.note !== '') commands.push(make('task.setNote', id, { note: p.note }));
      if (p.completed === true) commands.push(make('task.setCompleted', id, { completed: true }));
      if (p.schedule !== undefined && p.schedule !== null) {
        commands.push(make('task.setSchedule', id, { schedule: p.schedule }));
      }
      if (deleted) commands.push(make('task.delete', id, {}));
      continue;
    }

    if (deleted && cur.lifecycle === 'active') commands.push(make('task.delete', id, {}));
    if (!deleted && cur.lifecycle === 'deleted') commands.push(make('task.restore', id, {}));
    if (deleted) continue;

    const title = typeof p.title === 'string' ? p.title : '';
    if (cur.title !== title) commands.push(make('task.setTitle', id, { title }));

    const note = typeof p.note === 'string' ? p.note : '';
    if ((cur.note ?? '') !== note) commands.push(make('task.setNote', id, { note }));

    const completed = Boolean(p.completed);
    if (Boolean(cur.completed) !== completed) commands.push(make('task.setCompleted', id, { completed }));

    const itemType = typeof p.itemType === 'string' ? p.itemType : cur.itemType;
    if (itemType && cur.itemType !== itemType) commands.push(make('task.changeType', id, { itemType }));

    if (p.schedule !== undefined && JSON.stringify(cur.schedule) !== JSON.stringify(p.schedule ?? null)) {
      commands.push(make('task.setSchedule', id, { schedule: p.schedule ?? null }));
    }
  }
  return commands;
}

export function diffJournalsToCommands(state, incoming) {
  const commands = [];
  for (const [date, entry] of Object.entries(incoming ?? {})) {
    const value = typeof entry === 'string' ? { text: entry } : entry;
    const cur = state.journals[date];
    const text = value?.text ?? '';
    if (value?.deletedAt) {
      if (cur && cur.lifecycle === 'active') commands.push(make('journal.delete', date, {}));
      continue;
    }
    if (!cur) {
      if (text !== '') commands.push(make('journal.setText', date, { text }));
    } else if (cur.lifecycle === 'active' && cur.text !== text) {
      commands.push(make('journal.setText', date, { text }));
    }
    // 已删 journal 的"复活写":V3 无 journal.restore,不发命令(见文件头限制)
  }
  return commands;
}

export function diffGoalsToCommands(state, incoming) {
  const commands = [];
  for (const g of Array.isArray(incoming) ? incoming : []) {
    const id = g?.id;
    if (id == null || id === '') continue;
    const cur = state.goals[id];
    const p = g.payload ?? {};
    const { lifecycle, deletedAt, ...fields } = p;

    if (g.deletedAt) {
      if (cur && cur.lifecycle === 'active') commands.push(make('goal.delete', id, {}));
      continue;
    }
    if (!cur) {
      commands.push(make('goal.create', id, { title: fields.title ?? '' }));
      const rest = { ...fields };
      delete rest.title;
      if (Object.keys(rest).length > 0) commands.push(make('goal.patch', id, { patch: rest }));
      continue;
    }
    if (cur.lifecycle === 'deleted') continue; // 无 goal.restore(见文件头限制)

    const patch = {};
    for (const [k, v] of Object.entries(fields)) {
      if (JSON.stringify(cur[k]) !== JSON.stringify(v)) patch[k] = v;
    }
    if (Object.keys(patch).length > 0) commands.push(make('goal.patch', id, { patch }));
  }
  return commands;
}

export function diffInsightsToCommands(state, incoming) {
  const commands = [];
  const { entries = [], reports = {} } = incoming ?? {};

  for (const i of entries) {
    const id = i?.id;
    if (id == null || id === '') continue;
    const { id: _id, updatedAt, deletedAt, ...payload } = i;
    const cur = state.insights[id];
    if (deletedAt) {
      if (cur && cur.lifecycle === 'active') commands.push(make('insight.delete', id, {}));
      continue;
    }
    if (cur?.lifecycle === 'deleted') continue;
    if (JSON.stringify(cur) !== JSON.stringify({ ...payload, lifecycle: 'active', deletedAt: null })) {
      commands.push(make('insight.upsert', id, { payload }));
    }
  }

  for (const [date, r] of Object.entries(reports)) {
    const id = `report-${date}`;
    const { updatedAt, deletedAt, ...payload } = r ?? {};
    const cur = state.insights[id];
    if (deletedAt) {
      if (cur && cur.lifecycle === 'active') commands.push(make('insight.delete', id, {}));
      continue;
    }
    if (cur?.lifecycle === 'deleted') continue;
    const next = { ...payload, date, lifecycle: 'active', deletedAt: null };
    if (JSON.stringify(cur) !== JSON.stringify(next)) {
      commands.push(make('insight.upsert', id, { payload: { ...payload, date } }));
    }
  }
  return commands;
}

// ── 适配器(含序列分配与回执等待)──────────────────────────────────────────

export function createLegacyAdapter({ db, publisher, log = console, writesDisabled = false }) {
  // legacy PUT 串行化:序列分配与回执等待之间不允许交错
  let chain = Promise.resolve();
  const serialize = (fn) => {
    const run = chain.then(fn, fn);
    chain = run.catch(() => {});
    return run;
  };

  async function publishCommands(commands) {
    if (commands.length === 0) return;
    const base = acceptedThrough(db, LEGACY_DEVICE_ID) + 1;
    const batch = {
      protocolVersion: 3,
      batchId: randomUUID(),
      deviceId: LEGACY_DEVICE_ID,
      firstClientSequence: base,
      lastClientSequence: base + commands.length - 1,
      commands: commands.map((c, i) => ({ ...c, commandId: randomUUID(), clientSequence: base + i })),
    };
    await publisher.publish(batch);

    // 整合在另一进程:轮询回执,最多 5s(§21 等待中央回执)
    const deadline = Date.now() + 5_000;
    while (Date.now() < deadline) {
      const pending = commands.some((c) => !findReceipt(db, c.commandId));
      if (!pending) return;
      await new Promise((r) => setTimeout(r, 100));
    }
    log.warn({ batchId: batch.batchId, commands: commands.length }, 'legacy PUT timed out waiting for receipts');
  }

  const putDataset = (diffFn, projectFn) => (incoming) =>
    serialize(async () => {
      if (writesDisabled) {
        const err = new Error('V1 writes are disabled; upgrade to the V3 protocol');
        err.code = 'V1_WRITES_DISABLED';
        throw err;
      }
      const state = loadStateFromDb(db);
      await publishCommands(diffFn(state, incoming));
      return projectFn();
    });

  const currentRevision = () =>
    db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get()?.version ?? 0;

  return {
    writesDisabled,
    getEvents: () => projectLegacy(db).events,
    getJournals: () => projectLegacy(db).journals,
    getGoals: () => projectLegacy(db).goals,
    getInsights: () => projectLegacy(db).insights,

    putEvents: (incoming) => putDataset(diffEventsToCommands, () => projectLegacy(db).events)(incoming),
    putJournals: (incoming) => putDataset(diffJournalsToCommands, () => projectLegacy(db).journals)(incoming),
    putGoals: (incoming) => putDataset(diffGoalsToCommands, () => projectLegacy(db).goals)(incoming),
    putInsights: (incoming) => putDataset(diffInsightsToCommands, () => projectLegacy(db).insights)(incoming),

    // 旧格式长轮询:revision = 快照版本;新版本发布即唤醒,否则挂起 maxWaitMs
    async changes({ since = 0, maxWaitMs = 25_000, pollMs = 500 } = {}) {
      const deadline = Date.now() + maxWaitMs;
      while (Date.now() < deadline) {
        const revision = currentRevision();
        if (revision > since) {
          return { revision, datasets: ['events', 'journals', 'goals', 'insights'] };
        }
        await new Promise((r) => setTimeout(r, pollMs));
      }
      return { revision: currentRevision(), datasets: [] };
    },

    serverTime: () => ({ time: Date.now() }),
  };
}
