// 有序 materializer 核心(见 docs/sync-v3.md §5/§6/§9/§10)。
//
// 职责:按 broker sequence 顺序应用中央 reducer,单 SQLite 事务写
// entities / 回执 / 快照 / latest / 发布 outbox。不直接接触 NATS ——
// 消息源由 main.js 注入,便于确定性重放测试。
//
// 铁律:
//   - 一个 integration batch 一条事务、最多一个快照(§6)。
//   - 重复 commandId 返回原回执,不重复执行(§5/§10)。
//   - reducer 抛错 → 事务回滚、状态不变,由上层停止消费等待重投,绝不静默跳过。
import { randomUUID } from 'node:crypto';
import { emptyState } from './reducer.js';
import { findReceipt, insertReceipt } from '../state/receipts.js';
import { buildSnapshot } from './snapshot.js';

// reducer 状态 map 键 → entities 表 entity_type
const ENTITY_TYPES = {
  tasks: 'task',
  customLists: 'customList',
  journals: 'journal',
  goals: 'goal',
  insights: 'insight',
};

// reducer 实体把 lifecycle/deletedAt 放在实体内;entities 表把它们拆成独立列。
function splitLifecycle(entity) {
  const { lifecycle, deletedAt, ...payload } = entity;
  return { lifecycle, deletedAt, payload };
}

export function loadStateFromDb(db) {
  const state = emptyState();
  const rows = db
    .prepare('SELECT entity_type, entity_id, lifecycle, payload_json, deleted_at FROM entities')
    .all();
  for (const row of rows) {
    const mapKey = Object.keys(ENTITY_TYPES).find((k) => ENTITY_TYPES[k] === row.entity_type);
    if (!mapKey) continue;
    const payload = JSON.parse(row.payload_json);
    state[mapKey][row.entity_id] = { ...payload, lifecycle: row.lifecycle, deletedAt: row.deleted_at };
  }
  return state;
}

// reducer 不可变更新:未变实体保持同一引用,引用不等即已变化。
function changedKeys(oldMap, newMap) {
  const keys = new Set([...Object.keys(oldMap ?? {}), ...Object.keys(newMap ?? {})]);
  return [...keys].filter((id) => oldMap?.[id] !== newMap?.[id]);
}

export function createMaterializer({
  db,
  applyCommand,
  serverInstanceId,
  now = () => Date.now(),
  buildSnapshotFn = buildSnapshot,
}) {
  let state = loadStateFromDb(db);
  let snapshotVersion = db.prepare('SELECT COALESCE(MAX(version), 0) AS v FROM snapshots').get().v;

  const upsertEntity = db.prepare(`
    INSERT INTO entities
      (entity_type, entity_id, lifecycle, payload_json, last_broker_sequence,
       created_at, updated_at, deleted_at)
    VALUES
      (@entityType, @entityId, @lifecycle, @payloadJson, @brokerSequence, @now, @now, @deletedAt)
    ON CONFLICT(entity_type, entity_id) DO UPDATE SET
      lifecycle = excluded.lifecycle,
      payload_json = excluded.payload_json,
      last_broker_sequence = excluded.last_broker_sequence,
      updated_at = excluded.updated_at,
      deleted_at = excluded.deleted_at
  `);

  const insertSnapshot = db.prepare(`
    INSERT INTO snapshots
      (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
       state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
       created_at)
    VALUES
      (@version, @parentVersion, @brokerFromSequence, @brokerToSequence, 3,
       @stateHash, @compressedHash, @compressedPayload, @uncompressedBytes, @compressedBytes,
       @now)
  `);

  const upsertLatest = db.prepare(`
    INSERT INTO latest_snapshot (singleton_id, version, state_hash)
    VALUES (1, @version, @stateHash)
    ON CONFLICT(singleton_id) DO UPDATE SET
      version = excluded.version,
      state_hash = excluded.state_hash
  `);

  const insertOutbox = db.prepare(`
    INSERT INTO publication_outbox
      (publication_id, publication_type, dedupe_key, payload_json, state,
       attempt_count, next_attempt_at, created_at)
    VALUES
      (@publicationId, 'snapshot.ready', @dedupeKey, @payloadJson, 'pending', 0, 0, @now)
  `);

  /**
   * 处理一个 integration batch(§6)。
   * 每条 entry = { brokerSequence, deviceId, batchId?, command };按 brokerSequence 升序。
   * 返回 { receipts, snapshot }:snapshot 为 null 表示整批无状态变化(全部 NOOP/REJECTED)。
   */
  function processIntegrationBatch(entries) {
    const ordered = [...entries].sort((a, b) => a.brokerSequence - b.brokerSequence);

    // 1. 内存 dry-run:去重 + 顺序 apply(纯函数,不进事务)
    let nextState = state;
    let minSeq = Infinity;
    let maxSeq = -Infinity;
    let changed = false;
    const seen = new Map(); // commandId → 首次出现的 receipt
    const outcomes = [];

    for (const entry of ordered) {
      const { brokerSequence, command } = entry;
      minSeq = Math.min(minSeq, brokerSequence);
      maxSeq = Math.max(maxSeq, brokerSequence);

      const existing = findReceipt(db, command.commandId);
      if (existing) {
        outcomes.push({ entry, receipt: existing, isNew: false });
        continue;
      }
      if (seen.has(command.commandId)) {
        outcomes.push({ entry, receipt: seen.get(command.commandId), isNew: false });
        continue;
      }

      const result = applyCommand(nextState, command, brokerSequence);
      seen.set(command.commandId, result.receipt);
      outcomes.push({ entry, receipt: result.receipt, isNew: true });
      if (result.state !== nextState) {
        nextState = result.state;
        changed = true;
      }
    }

    // 2. 有状态变化才生成快照;版本只前进(§6)
    const newVersion = changed ? snapshotVersion + 1 : snapshotVersion;
    const snapshot = changed
      ? buildSnapshotFn({
          state: nextState,
          snapshotVersion: newVersion,
          parentVersion: snapshotVersion,
          serverInstanceId,
          brokerFromSequence: minSeq,
          brokerToSequence: maxSeq,
        })
      : null;

    // 3. 单事务落库:回执 + 实体 diff + 快照 + latest + 发布 outbox(§10)
    const writeTx = db.transaction(() => {
      for (const { entry, receipt, isNew } of outcomes) {
        if (!isNew) continue;
        const { brokerSequence, deviceId, batchId, command } = entry;
        insertReceipt(db, {
          commandId: command.commandId,
          batchId: batchId ?? '',
          deviceId,
          clientSequence: command.clientSequence,
          brokerSequence,
          commandType: command.type,
          aggregateId: command.aggregateId ?? null,
          status: receipt.status,
          errorCode: receipt.errorCode ?? null,
          snapshotVersion:
            receipt.status === 'APPLIED' ? (snapshot?.manifest.snapshotVersion ?? null) : null,
          resultJson: null,
          processedAt: now(),
        });
      }

      if (snapshot) {
        for (const [mapKey, entityType] of Object.entries(ENTITY_TYPES)) {
          for (const id of changedKeys(state[mapKey], nextState[mapKey])) {
            const entity = nextState[mapKey][id];
            if (!entity) continue; // reducer 从不移除实体,仅为防御
            const { lifecycle, deletedAt, payload } = splitLifecycle(entity);
            upsertEntity.run({
              entityType,
              entityId: id,
              lifecycle,
              payloadJson: JSON.stringify(payload),
              brokerSequence: maxSeq,
              now: now(),
              deletedAt: deletedAt ?? null,
            });
          }
        }

        insertSnapshot.run({
          version: snapshot.manifest.snapshotVersion,
          parentVersion: snapshot.manifest.parentVersion,
          brokerFromSequence: minSeq,
          brokerToSequence: maxSeq,
          stateHash: snapshot.stateHash,
          compressedHash: snapshot.compressedHash,
          compressedPayload: snapshot.compressed,
          uncompressedBytes: snapshot.manifest.uncompressedBytes,
          compressedBytes: snapshot.manifest.compressedBytes,
          now: now(),
        });
        upsertLatest.run({
          version: snapshot.manifest.snapshotVersion,
          stateHash: snapshot.stateHash,
        });
        insertOutbox.run({
          publicationId: randomUUID(),
          dedupeKey: `snapshot.ready:v${snapshot.manifest.snapshotVersion}`,
          payloadJson: JSON.stringify(snapshot.manifest),
          now: now(),
        });
      }
    });

    writeTx(); // 事务失败抛错:回滚,内存状态不变

    if (snapshot) snapshotVersion = newVersion;
    state = nextState;

    return {
      receipts: outcomes.map(({ entry, receipt }) => ({
        brokerSequence: entry.brokerSequence,
        deviceId: entry.deviceId,
        commandId: entry.command.commandId,
        status: receipt.status,
        errorCode: receipt.errorCode,
      })),
      snapshot,
    };
  }

  return {
    processIntegrationBatch,
    getState: () => state,
    getSnapshotVersion: () => snapshotVersion,
  };
}
