// Change journal:权威状态的 materialized diff(见 docs/sync-v3.md §9.1)。
//
// 设计铁律:
//   - journal commit identity == snapshotVersion,不另造全局 sequence;
//   - 每条 change 都是 State Builder 已裁决的完整 canonical entity
//     (`<entityType>.put`),客户端只安装结果,绝不重放 reducer;
//   - changes=[] 的 empty commit 必须保留(NOOP/REJECTED coverage);
//   - insertJournalCommit 只在调用方已开启的事务内写行,绝不自己开事务,
//     保证与 snapshot 同一 SQLite transaction 提交。
//
// reducer 从不物理删除实体(删除是 lifecycle/deletedAt),因此 put 永远携带
// 完整实体值;若发现实体从 state 消失,立刻抛错而不是静默跳过。
export const ENTITY_TYPES = {
  tasks: 'task',
  customLists: 'customList',
  journals: 'journal',
  goals: 'goal',
  insights: 'insight',
};

export const MAP_KEY_BY_ENTITY_TYPE = Object.fromEntries(
  Object.entries(ENTITY_TYPES).map(([mapKey, entityType]) => [entityType, mapKey]),
);

export const JOURNAL_SCHEMA_VERSION = 3;

// reducer 不可变更新:未变实体保持同一引用,引用不等即已变化。
export function changedKeys(oldMap, newMap) {
  const keys = new Set([...Object.keys(oldMap ?? {}), ...Object.keys(newMap ?? {})]);
  return [...keys].filter((id) => oldMap?.[id] !== newMap?.[id]);
}

/**
 * 比较两个 reducer state,产出本 commit 的 typed authoritative changes。
 * 顺序确定(tasks → customLists → journals → goals → insights)。
 */
export function computeJournalChanges({ fromState, toState, brokerToSequence }) {
  const changes = [];
  for (const [mapKey, entityType] of Object.entries(ENTITY_TYPES)) {
    for (const id of changedKeys(fromState[mapKey], toState[mapKey])) {
      const value = toState[mapKey][id];
      if (value === undefined) {
        throw new Error(
          `V3 invariant violated: reducer physically removed ${entityType} ${id}`,
        );
      }
      changes.push({
        type: `${entityType}.put`,
        entityType,
        entityId: id,
        entityBrokerSequence: brokerToSequence,
        value,
      });
    }
  }
  return changes;
}

/**
 * 在调用方事务内写一个 journal commit。changes 可以为 []。
 */
export function insertJournalCommit(
  db,
  {
    snapshotVersion,
    parentVersion,
    brokerFromSequence,
    brokerToSequence,
    stateHashAfter,
    changes,
    createdAt,
    schemaVersion = JOURNAL_SCHEMA_VERSION,
  },
) {
  const payloads = changes.map((change) => JSON.stringify(change.value));
  const payloadBytes = payloads.reduce(
    (total, payload) => total + Buffer.byteLength(payload, 'utf8'),
    0,
  );

  db.prepare(`
    INSERT INTO change_commits
      (snapshot_version, parent_version, broker_from_sequence, broker_to_sequence,
       schema_version, state_hash_after, change_count, payload_bytes, created_at)
    VALUES
      (@snapshotVersion, @parentVersion, @brokerFromSequence, @brokerToSequence,
       @schemaVersion, @stateHashAfter, @changeCount, @payloadBytes, @createdAt)
  `).run({
    snapshotVersion,
    parentVersion,
    brokerFromSequence,
    brokerToSequence,
    schemaVersion,
    stateHashAfter,
    changeCount: changes.length,
    payloadBytes,
    createdAt,
  });

  const insertItem = db.prepare(`
    INSERT INTO change_items
      (snapshot_version, ordinal, change_type, entity_type, entity_id,
       entity_broker_sequence, payload_json)
    VALUES
      (@snapshotVersion, @ordinal, @changeType, @entityType, @entityId,
       @entityBrokerSequence, @payloadJson)
  `);
  changes.forEach((change, ordinal) => {
    insertItem.run({
      snapshotVersion,
      ordinal,
      changeType: change.type,
      entityType: change.entityType,
      entityId: change.entityId,
      entityBrokerSequence: change.entityBrokerSequence,
      payloadJson: payloads[ordinal],
    });
  });
}

export function getJournalMeta(db) {
  const row = db
    .prepare('SELECT journal_epoch, min_snapshot_version FROM sync_journal_meta WHERE singleton_id = 1')
    .get();
  return row
    ? { journalEpoch: row.journal_epoch, minSnapshotVersion: row.min_snapshot_version }
    : null;
}

/**
 * 读取一个 journal commit(供测试、shadow validator 与未来 /changes 使用)。
 * value 由 payload_json 解析为完整 canonical entity(含 lifecycle/deletedAt)。
 */
export function loadJournalCommit(db, snapshotVersion) {
  const row = db
    .prepare('SELECT * FROM change_commits WHERE snapshot_version = ?')
    .get(snapshotVersion);
  if (!row) return null;
  const items = db
    .prepare(`
      SELECT change_type, entity_type, entity_id, entity_broker_sequence, payload_json
        FROM change_items
       WHERE snapshot_version = ?
       ORDER BY ordinal
    `)
    .all(snapshotVersion);
  return {
    snapshotVersion: row.snapshot_version,
    parentVersion: row.parent_version,
    brokerFromSequence: row.broker_from_sequence,
    brokerToSequence: row.broker_to_sequence,
    schemaVersion: row.schema_version,
    stateHashAfter: row.state_hash_after,
    changeCount: row.change_count,
    payloadBytes: row.payload_bytes,
    changes: items.map((item) => ({
      type: item.change_type,
      entityType: item.entity_type,
      entityId: item.entity_id,
      entityBrokerSequence: item.entity_broker_sequence,
      value: JSON.parse(item.payload_json),
    })),
  };
}

/**
 * 纯函数:把一个 commit 的 authoritative changes 安装到 state 上。
 * Snapshot(N) + Commit(N+1) == Snapshot(N+1) 的 reconstruction 核心,
 * 与未来客户端 installer 采用完全相同的语义(整实体替换,无物理删除)。
 */
export function applyJournalCommit(state, commit) {
  let next = state;
  for (const change of commit.changes) {
    const mapKey = MAP_KEY_BY_ENTITY_TYPE[change.entityType];
    if (!mapKey || change.type !== `${change.entityType}.put`) {
      throw new Error(`unsupported journal change: ${change.type}`);
    }
    next = { ...next, [mapKey]: { ...next[mapKey], [change.entityId]: change.value } };
  }
  return next;
}

/**
 * 批量读取 cursor 之后的连续 commits(含 empty commit,绝不因 changes=[] 过滤)。
 * 按 snapshotVersion 升序;分页边界永远落在 commit 之间。
 */
export function loadJournalCommitRange(db, { fromSnapshotVersion, limit }) {
  const rows = db
    .prepare(`
      SELECT snapshot_version, parent_version, broker_from_sequence, broker_to_sequence,
             state_hash_after
        FROM change_commits
       WHERE snapshot_version > ?
       ORDER BY snapshot_version
       LIMIT ?
    `)
    .all(fromSnapshotVersion, limit);
  if (rows.length === 0) return [];

  const itemStmt = db.prepare(`
    SELECT change_type, entity_type, entity_id, entity_broker_sequence, payload_json
      FROM change_items
     WHERE snapshot_version = ?
     ORDER BY ordinal
  `);
  return rows.map((row) => ({
    snapshotVersion: row.snapshot_version,
    parentVersion: row.parent_version,
    brokerFromSequence: row.broker_from_sequence,
    brokerToSequence: row.broker_to_sequence,
    stateHashAfter: row.state_hash_after,
    changes: itemStmt.all(row.snapshot_version).map((item) => ({
      type: item.change_type,
      entityType: item.entity_type,
      entityId: item.entity_id,
      entityBrokerSequence: item.entity_broker_sequence,
      value: JSON.parse(item.payload_json),
    })),
  }));
}

/**
 * wire DTO:去掉内部 entityType 列,只保留客户端安装所需的字段。
 */
export function toWireCommit(commit) {
  return {
    snapshotVersion: commit.snapshotVersion,
    parentVersion: commit.parentVersion,
    brokerFromSequence: commit.brokerFromSequence,
    brokerToSequence: commit.brokerToSequence,
    stateHashAfter: commit.stateHashAfter,
    changes: commit.changes.map(({ type, entityId, entityBrokerSequence, value }) => ({
      type,
      entityId,
      entityBrokerSequence,
      value,
    })),
  };
}

/**
 * Journal retention(§9.4):journal 是有限历史,latest full snapshot 永远可以
 * 重建。剪掉 cutoff 及更早的 commits(change_items 级联),并把
 * min_snapshot_version 单调推进到 cutoff —— cursor.snapshotVersion < min
 * 即 410 → full snapshot,绝不为一台旧设备无限保留 journal。
 *
 * cutoff = max(min_snapshot_version, head - keepCommits, 按 keepAgeMs 算出的
 * 最老保留版本),且绝不剪掉 head commit 本身。幂等、单调。
 */
export function pruneJournal(db, { keepCommits, keepAgeMs, now = Date.now() }) {
  if (!Number.isSafeInteger(keepCommits) || keepCommits < 1) {
    throw new Error('keepCommits must be a positive safe integer');
  }
  if (!Number.isSafeInteger(keepAgeMs) || keepAgeMs < 0) {
    throw new Error('keepAgeMs must be a non-negative safe integer');
  }
  const prune = db.transaction(() => {
    const meta = db
      .prepare('SELECT min_snapshot_version FROM sync_journal_meta WHERE singleton_id = 1')
      .get();
    if (!meta) return { prunedCommits: 0, minSnapshotVersion: 0 };
    const head = db
      .prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1')
      .get()?.version ?? 0;
    if (head <= 1) return { prunedCommits: 0, minSnapshotVersion: meta.min_snapshot_version };

    let cutoff = Math.max(meta.min_snapshot_version, head - keepCommits);
    if (keepAgeMs > 0) {
      const ageCutoff = db
        .prepare('SELECT COALESCE(MAX(snapshot_version), 0) AS v FROM change_commits WHERE created_at < ?')
        .get(now() - keepAgeMs).v;
      cutoff = Math.max(cutoff, ageCutoff);
    }
    if (cutoff >= head) cutoff = head - 1;
    if (cutoff <= meta.min_snapshot_version) {
      return { prunedCommits: 0, minSnapshotVersion: meta.min_snapshot_version };
    }

    const info = db.prepare('DELETE FROM change_commits WHERE snapshot_version <= ?').run(cutoff);
    db.prepare('UPDATE sync_journal_meta SET min_snapshot_version = ? WHERE singleton_id = 1')
      .run(cutoff);
    return { prunedCommits: info.changes, minSnapshotVersion: cutoff };
  });
  return prune();
}
