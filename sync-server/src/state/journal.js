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
