// 有序 materializer 核心(见 docs/sync-v3.md §5/§6/§9/§10)。
//
// 职责:按 broker sequence 顺序应用中央 reducer,单 SQLite 事务写
// entities / 回执 / 设备进度 / 快照 / latest / 发布 outbox。不直接接触
// NATS —— 消息源由 main.js 注入,便于确定性重放测试。
//
// 铁律:
//   - 一个 integration batch 一条事务、最多一个快照(§6)。
//   - 重复 commandId 返回原回执,不重复执行(§5/§10)。
//   - clientSequence 缺口:拒绝 SEQUENCE_GAP、不推进 accepted;客户端重传补齐
//     缺口后重新裁决(§5)。
//   - reducer 抛错 → 事务回滚、状态不变,由上层停止消费等待重投,绝不静默跳过。
import { randomUUID } from 'node:crypto';
import { emptyState } from './reducer.js';
import {
  deleteReceipt,
  deleteReceiptByDeviceSequence,
  findReceipt,
  findReceiptByDeviceSequence,
  insertReceipt,
} from '../state/receipts.js';
import { buildSnapshot } from './snapshot.js';
import { canonicalizeTaskPayload } from '../state/canonicalTask.js';

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
    const rawPayload = JSON.parse(row.payload_json);
    const payload = row.entity_type === 'task' ? canonicalizeTaskPayload(rawPayload) : rawPayload;
    state[mapKey][row.entity_id] = { ...payload, lifecycle: row.lifecycle, deletedAt: row.deleted_at };
  }
  return state;
}

export function loadProgressFromDb(db) {
  const accepted = new Map();
  for (const row of db.prepare('SELECT device_id, accepted_client_sequence FROM device_progress').all()) {
    accepted.set(row.device_id, row.accepted_client_sequence);
  }
  return accepted;
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
  assertWriterLease = () => {},
}) {
  let state = loadStateFromDb(db);
  let snapshotVersion = db.prepare('SELECT COALESCE(MAX(version), 0) AS v FROM snapshots').get().v;
  const acceptedSeq = loadProgressFromDb(db);

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

  const upsertProgress = db.prepare(`
    INSERT INTO device_progress
      (device_id, accepted_client_sequence, installed_snapshot_version,
       installed_snapshot_hash, last_seen_at, protocol_version)
    VALUES (@deviceId, @accepted, 0, NULL, @lastSeenAt, 3)
    ON CONFLICT(device_id) DO UPDATE SET
      accepted_client_sequence = MAX(accepted_client_sequence, excluded.accepted_client_sequence),
      last_seen_at = excluded.last_seen_at
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
   * 返回 { receipts, snapshot }:snapshot 为 null 表示本批没有新终态回执
   * (纯 SEQUENCE_GAP 或全部是已处理重投)。新接受的 NOOP/REJECTED 等即使
   * 不改变 state，也必须发布同 stateHash 的 coverage snapshot，让客户端能以
   * snapshot brokerToSequence 证明终态回执已经进入不可变历史。
   */
  function processIntegrationBatch(entries) {
    const ordered = [...entries].sort((a, b) => a.brokerSequence - b.brokerSequence);

    // 1. 内存 dry-run:去重 + 顺序 apply(纯函数,不进事务)
    let nextState = state;
    const seen = new Map(); // commandId → 首次出现的 receipt
    const outcomes = [];
    const progressUpdates = new Map(); // deviceId → 本批最高 accepted clientSequence
    // Dry-run 只修改工作副本。reducer/buildSnapshot 抛错时，已提交进度不能被
    // 内存提前推进，否则同一进程中的重投会被误判成重复序列。
    const workingAcceptedSeq = new Map(acceptedSeq);

    for (const entry of ordered) {
      const { brokerSequence, command } = entry;
      // 曾因缺口被拒(SEQUENCE_GAP):重传 → 重新裁决,旧回执在本批事务内删除重写
      const existing = findReceipt(db, command.commandId);
      const retryGap = existing !== undefined && existing.status === 'SEQUENCE_GAP';
      if (existing && !retryGap) {
        outcomes.push({ entry, receipt: existing, isNew: false, retryGap: false });
        continue;
      }
      if (!retryGap && seen.has(command.commandId)) {
        outcomes.push({ entry, receipt: seen.get(command.commandId), isNew: false, retryGap: false });
        continue;
      }

      const accepted = workingAcceptedSeq.get(entry.deviceId) ?? 0;

      // 同槽位已有 SEQUENCE_GAP 回执(可能来自另一个 commandId 的历史重传):
      // 视为客户端补齐重传,事务内先删旧 GAP 行再重裁,绝不让 GAP 行堵死序列槽位。
      const existingBySeq = findReceiptByDeviceSequence(db, entry.deviceId, command.clientSequence);
      const seqRetryGap = existingBySeq?.status === 'SEQUENCE_GAP';

      // 已接受的序列:客户端重发 → 返回原回执,不重复执行
      if (command.clientSequence <= accepted && !seqRetryGap) {
        const receipt = existingBySeq ?? { status: 'NOOP', errorCode: 'DUPLICATE_CLIENT_SEQUENCE' };
        outcomes.push({ entry, receipt, isNew: false, retryGap, seqRetryGap: false });
        continue;
      }

      // 序列缺口:终态拒绝写回执,不推进 accepted;客户端重传补齐后重裁
      if (command.clientSequence > accepted + 1) {
        const receipt = { status: 'SEQUENCE_GAP', errorCode: 'SEQUENCE_GAP' };
        seen.set(command.commandId, receipt);
        outcomes.push({ entry, receipt, isNew: true, retryGap, seqRetryGap });
        continue;
      }

      const result = applyCommand(nextState, command, brokerSequence);
      seen.set(command.commandId, result.receipt);
      outcomes.push({ entry, receipt: result.receipt, isNew: true, retryGap, seqRetryGap, advanceTo: command.clientSequence });
      workingAcceptedSeq.set(entry.deviceId, command.clientSequence);
      progressUpdates.set(entry.deviceId, command.clientSequence);
      if (result.state !== nextState) {
        nextState = result.state;
      }
    }

    // 2. 每个新终态回执都必须被不可变快照覆盖。SEQUENCE_GAP 是可重裁的
    // 临时结果；历史 commandId/clientSequence 重投没有新回执，二者均不发布。
    const coveredOutcomes = outcomes.filter(
      ({ isNew, receipt }) => isNew && receipt.status !== 'SEQUENCE_GAP',
    );
    const publishesCoverage = coveredOutcomes.length > 0;
    const brokerFromSequence = publishesCoverage
      ? Math.min(...coveredOutcomes.map(({ entry }) => entry.brokerSequence))
      : null;
    const brokerToSequence = publishesCoverage
      ? Math.max(...coveredOutcomes.map(({ entry }) => entry.brokerSequence))
      : null;
    const newVersion = publishesCoverage ? snapshotVersion + 1 : snapshotVersion;
    const snapshot = publishesCoverage
      ? buildSnapshotFn({
          state: nextState,
          snapshotVersion: newVersion,
          parentVersion: snapshotVersion,
          serverInstanceId,
          brokerFromSequence,
          brokerToSequence,
        })
      : null;

    // 3. 单事务落库:重裁删除 → 回执 → 设备进度 → 实体 diff → 快照 → outbox(§10)
    const writeTx = db.transaction(() => {
      // Fencing happens under the same SQLite write lock as the authoritative commit. If a slow
      // former owner built this batch after its lease expired, a new owner can change the row
      // before this transaction, but it cannot race between this check and the writes below.
      assertWriterLease();
      for (const { entry, retryGap, seqRetryGap } of outcomes) {
        if (retryGap) deleteReceipt(db, entry.command.commandId);
        if (seqRetryGap) deleteReceiptByDeviceSequence(db, entry.deviceId, entry.command.clientSequence);
      }
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
          snapshotVersion: receipt.status === 'SEQUENCE_GAP'
            ? null
            : snapshot?.manifest.snapshotVersion ?? null,
          resultJson: null,
          processedAt: now(),
        });
      }
      for (const [deviceId, accepted] of progressUpdates) {
        upsertProgress.run({ deviceId, accepted, lastSeenAt: now() });
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
              brokerSequence: brokerToSequence,
              now: now(),
              deletedAt: deletedAt ?? null,
            });
          }
        }

        insertSnapshot.run({
          version: snapshot.manifest.snapshotVersion,
          parentVersion: snapshot.manifest.parentVersion,
          brokerFromSequence,
          brokerToSequence,
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

    writeTx(); // 事务失败抛错:DB 与内存均保持已提交状态

    if (snapshot) snapshotVersion = newVersion;
    acceptedSeq.clear();
    for (const [deviceId, accepted] of workingAcceptedSeq) acceptedSeq.set(deviceId, accepted);
    state = nextState;

    return {
      receipts: outcomes.map(({ entry, receipt }) => ({
        brokerSequence: entry.brokerSequence,
        deviceId: entry.deviceId,
        commandId: receipt.commandId ?? entry.command.commandId,
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
    getAcceptedSequence: (deviceId) => acceptedSeq.get(deviceId) ?? 0,
  };
}

/**
 * 修复旧版本留下的“终态回执已提交，但 latest snapshot 的 broker watermark
 * 尚未覆盖”窗口。只覆盖非 SEQUENCE_GAP 回执；新快照复用当前权威 state，
 * 因而 stateHash 可与父版本相同。快照、latest、receipt 回填和发布 outbox
 * 在一个事务中提交，重复启动时 max broker 已被覆盖，返回 null。
 */
export function ensureReceiptCoverageSnapshot(
  db,
  {
    serverInstanceId,
    now = Date.now,
    buildSnapshotFn = buildSnapshot,
    assertWriterLease = () => {},
  } = {},
) {
  const latest = db.prepare(`
    SELECT s.version, s.broker_to_sequence, s.state_hash
    FROM latest_snapshot l
    JOIN snapshots s ON s.version = l.version
    WHERE l.singleton_id = 1
  `).get();
  if (!latest) return null;

  const uncovered = db.prepare(`
    SELECT MIN(broker_sequence) AS min_seq, MAX(broker_sequence) AS max_seq
    FROM processed_commands
    WHERE status <> 'SEQUENCE_GAP' AND broker_sequence > ?
  `).get(latest.broker_to_sequence);
  if (uncovered.max_seq == null) return null;

  const createdAt = now();
  const snapshot = buildSnapshotFn({
    state: loadStateFromDb(db),
    snapshotVersion: latest.version + 1,
    parentVersion: latest.version,
    serverInstanceId,
    brokerFromSequence: uncovered.min_seq,
    brokerToSequence: uncovered.max_seq,
    createdAt: new Date(createdAt).toISOString(),
  });

  db.transaction(() => {
    assertWriterLease();
    db.prepare(`
      INSERT INTO snapshots
        (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
         state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
         created_at)
      VALUES (?, ?, ?, ?, 3, ?, ?, ?, ?, ?, ?)
    `).run(
      snapshot.manifest.snapshotVersion,
      snapshot.manifest.parentVersion,
      uncovered.min_seq,
      uncovered.max_seq,
      snapshot.manifest.stateHash,
      snapshot.manifest.compressedHash,
      snapshot.compressed,
      snapshot.manifest.uncompressedBytes,
      snapshot.manifest.compressedBytes,
      createdAt,
    );
    db.prepare(`
      UPDATE processed_commands
      SET snapshot_version = ?
      WHERE snapshot_version IS NULL
        AND status <> 'SEQUENCE_GAP'
        AND broker_sequence <= ?
    `).run(snapshot.manifest.snapshotVersion, uncovered.max_seq);
    db.prepare(`
      INSERT INTO latest_snapshot (singleton_id, version, state_hash)
      VALUES (1, ?, ?)
      ON CONFLICT(singleton_id) DO UPDATE SET
        version = excluded.version,
        state_hash = excluded.state_hash
    `).run(snapshot.manifest.snapshotVersion, snapshot.manifest.stateHash);
    db.prepare(`
      INSERT INTO publication_outbox
        (publication_id, publication_type, dedupe_key, payload_json, state,
         attempt_count, next_attempt_at, created_at)
      VALUES (?, 'snapshot.ready', ?, ?, 'pending', 0, 0, ?)
    `).run(
      randomUUID(),
      `snapshot.ready:v${snapshot.manifest.snapshotVersion}`,
      JSON.stringify(snapshot.manifest),
      createdAt,
    );
  })();

  return snapshot;
}

/**
 * A fresh V3 installation still needs an immutable empty snapshot so readiness
 * and bootstrap clients have a well-defined starting point. Existing databases
 * are untouched. The snapshot and publication outbox row share one transaction.
 */
export function ensureBootstrapSnapshot(
  db,
  { serverInstanceId, now = Date.now, assertWriterLease = () => {} } = {},
) {
  const existing = db.prepare('SELECT MAX(version) AS v FROM snapshots').get().v;
  if (existing > 0) return null;
  const createdAt = now();

  const snapshot = buildSnapshot({
    state: loadStateFromDb(db),
    snapshotVersion: 1,
    parentVersion: 0,
    serverInstanceId,
    brokerFromSequence: 0,
    brokerToSequence: 0,
    createdAt: new Date(createdAt).toISOString(),
  });

  db.transaction(() => {
    assertWriterLease();
    db.prepare(`
      INSERT INTO snapshots
        (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
         state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
         created_at)
      VALUES (1, 0, 0, 0, 3, ?, ?, ?, ?, ?, ?)
    `).run(
      snapshot.manifest.stateHash,
      snapshot.manifest.compressedHash,
      snapshot.compressed,
      snapshot.manifest.uncompressedBytes,
      snapshot.manifest.compressedBytes,
      createdAt,
    );
    db.prepare(`
      INSERT INTO latest_snapshot (singleton_id, version, state_hash)
      VALUES (1, 1, ?)
    `).run(snapshot.manifest.stateHash);
    db.prepare(`
      INSERT INTO publication_outbox
        (publication_id, publication_type, dedupe_key, payload_json, state, attempt_count, next_attempt_at, created_at)
      VALUES (?, 'snapshot.ready', ?, ?, 'pending', 0, 0, ?)
    `).run(randomUUID(), 'snapshot.ready:v1', JSON.stringify(snapshot.manifest), createdAt);
  })();

  return snapshot.manifest;
}
