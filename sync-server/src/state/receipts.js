// processed_commands 回执仓库:插入、按 commandId 幂等查询、按设备拉取。
// 回执只在 SQLite 事务提交后可见(见 docs/sync-v3.md §10)。
const COLUMNS = [
  'command_id', 'batch_id', 'device_id', 'client_sequence', 'broker_sequence',
  'command_type', 'aggregate_id', 'status', 'error_code', 'snapshot_version',
  'result_json', 'processed_at',
];

const ROW_TO_CAMEL = (r) => r && ({
  commandId: r.command_id,
  batchId: r.batch_id,
  deviceId: r.device_id,
  clientSequence: r.client_sequence,
  brokerSequence: r.broker_sequence,
  commandType: r.command_type,
  aggregateId: r.aggregate_id,
  status: r.status,
  errorCode: r.error_code,
  snapshotVersion: r.snapshot_version,
  resultJson: r.result_json,
  processedAt: r.processed_at,
});

export function findReceipt(db, commandId) {
  const row = db.prepare(`SELECT ${COLUMNS} FROM processed_commands WHERE command_id = ?`).get(commandId);
  return ROW_TO_CAMEL(row);
}

export function findReceiptByDeviceSequence(db, deviceId, clientSequence) {
  const row = db
    .prepare(`SELECT ${COLUMNS} FROM processed_commands WHERE device_id = ? AND client_sequence = ?`)
    .get(deviceId, clientSequence);
  return ROW_TO_CAMEL(row);
}

// SEQUENCE_GAP 回执是临时裁决:客户端重传后必须重新裁决,因此允许删除重写。
export function deleteReceipt(db, commandId) {
  return db.prepare('DELETE FROM processed_commands WHERE command_id = ?').run(commandId);
}

// 按序列槽位删除:重传可能换了 commandId(不规范客户端/冒烟场景),
// 绝不让历史 GAP 行堵死 (device_id, client_sequence) 唯一约束。
export function deleteReceiptByDeviceSequence(db, deviceId, clientSequence) {
  return db
    .prepare('DELETE FROM processed_commands WHERE device_id = ? AND client_sequence = ?')
    .run(deviceId, clientSequence);
}

export function insertReceipt(db, r) {
  return db.prepare(`
    INSERT INTO processed_commands (${COLUMNS})
    VALUES (@commandId, @batchId, @deviceId, @clientSequence, @brokerSequence,
            @commandType, @aggregateId, @status, @errorCode, @snapshotVersion,
            @resultJson, @processedAt)
  `).run({
    commandId: r.commandId,
    batchId: r.batchId,
    deviceId: r.deviceId,
    clientSequence: r.clientSequence,
    brokerSequence: r.brokerSequence,
    commandType: r.commandType,
    aggregateId: r.aggregateId ?? null,
    status: r.status,
    errorCode: r.errorCode ?? null,
    snapshotVersion: r.snapshotVersion ?? null,
    resultJson: r.resultJson ?? null,
    processedAt: r.processedAt,
  });
}

export function receiptsForDeviceAfter(db, deviceId, afterClientSequence) {
  return db
    .prepare(
      `SELECT ${COLUMNS} FROM processed_commands
       WHERE device_id = ? AND client_sequence > ?
       ORDER BY client_sequence ASC LIMIT 200`,
    )
    .all(deviceId, afterClientSequence)
    .map(ROW_TO_CAMEL);
}

export function acceptedThrough(db, deviceId) {
  const row = db
    .prepare('SELECT MAX(client_sequence) AS max_seq FROM processed_commands WHERE device_id = ?')
    .get(deviceId);
  return row?.max_seq ?? 0;
}
