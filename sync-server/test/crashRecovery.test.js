import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase } from '../src/state/database.js';
import { createMaterializer } from '../src/materializer/materializer.js';
import { applyCommand, emptyState } from '../src/materializer/reducer.js';

const SERVER_ID = 'srv-crash-test';

const cmd = (type, aggregateId, args, commandId, clientSequence) => ({
  commandId,
  clientSequence,
  type,
  aggregateId,
  arguments: args ?? {},
});

function entry(brokerSequence, deviceId, command, batchId = 'b1') {
  return { brokerSequence, deviceId, batchId, command };
}

test('whole-batch redelivery after commit is a no-op (MQ at-least-once)', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  const entries = [
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
    entry(2, 'dev-1', cmd('task.setTitle', 't1', { title: 'b' }, 'c2', 2)),
  ];

  const first = m.processIntegrationBatch(entries);
  assert.equal(first.snapshot.manifest.snapshotVersion, 1);

  const redelivered = m.processIntegrationBatch(entries); // broker 重投同一批
  assert.equal(redelivered.snapshot, null, 'no new snapshot on redelivery');
  assert.equal(m.getState().tasks.t1.title, 'b');
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 1);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM processed_commands').get().c, 2);
  for (const r of redelivered.receipts) {
    assert.equal(r.status, first.receipts.find((x) => x.commandId === r.commandId).status);
  }
  db.close();
});

test('client resends with new commandIds after crash: sequences already accepted are not re-applied', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
  ]);

  // 客户端在 ACK 前崩溃,重启后用新 commandId 重发同一 clientSequence
  const resend = m.processIntegrationBatch([
    entry(2, 'dev-1', cmd('task.create', 't1', { title: 'a-dup' }, 'c1-new', 1)),
  ]);

  assert.equal(resend.snapshot, null);
  assert.equal(m.getState().tasks.t1.title, 'a', 'duplicate sequence must not re-apply');
  // 材料器返回原回执(status 沿用 c1 的 APPLIED),但不为新 commandId 落库:
  // 规范客户端总是重发同一 commandId,这里只防御性地不重复执行。
  assert.equal(resend.receipts[0].status, 'APPLIED');
  assert.equal(
    db.prepare("SELECT COUNT(*) AS c FROM processed_commands WHERE command_id = 'c1-new'").get().c,
    0,
  );
  db.close();
});

test('reducer throw rolls the whole batch back and leaves memory state intact', () => {
  const db = openDatabase(':memory:');
  const throwing = (state, command, seq) => {
    if (command.type === 'task.create' && command.aggregateId === 't2') {
      throw new Error('simulated internal reducer failure');
    }
    return applyCommand(state, command, seq);
  };
  const m = createMaterializer({ db, applyCommand: throwing, serverInstanceId: SERVER_ID });

  m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
  ]);
  const before = m.getState();

  assert.throws(() =>
    m.processIntegrationBatch([
      entry(2, 'dev-1', cmd('task.create', 't2', { title: 'b' }, 'c2', 2)),
      entry(3, 'dev-1', cmd('task.setTitle', 't1', { title: 'x' }, 'c3', 3)),
    ]),
  );

  // 事务回滚:没有任何本批痕迹,内存状态不变
  assert.equal(m.getState(), before);
  assert.equal(db.prepare("SELECT COUNT(*) AS c FROM processed_commands WHERE command_id = 'c2'").get().c, 0);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 1);
  assert.equal(m.getSnapshotVersion(), 1);
  db.close();
});

test('materializer rebuilds state from entities after simulated process restart', () => {
  const db = openDatabase(':memory:');
  const m1 = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  m1.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
    entry(2, 'dev-1', cmd('task.delete', 't1', {}, 'c2', 2)),
  ]);

  const m2 = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  assert.deepEqual(m2.getState(), m1.getState());
  assert.equal(m2.getState().tasks.t1.lifecycle, 'deleted');
  assert.equal(m2.getSnapshotVersion(), 1);
  db.close();
});

test('SEQUENCE_GAP then retransmit completes the sequence (receipt rewritten)', () => {
  const db = openDatabase(':memory:');
  const m = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  // 先到 seq1 与 seq3:seq3 有缺口 → GAP 回执
  const gapped = m.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
    entry(2, 'dev-1', cmd('task.setTitle', 't1', { title: 'gap' }, 'c3', 3)),
  ]);
  assert.equal(gapped.receipts.find((r) => r.commandId === 'c3').status, 'SEQUENCE_GAP');
  assert.equal(m.getState().tasks.t1.title, 'a');

  // 客户端重传缺失的 seq2 与 seq3(同 commandId)
  m.processIntegrationBatch([
    entry(3, 'dev-1', cmd('task.setNote', 't1', { note: 'n' }, 'c2', 2)),
    entry(4, 'dev-1', cmd('task.setTitle', 't1', { title: 'gap' }, 'c3', 3)),
  ]);

  const row = db.prepare("SELECT status FROM processed_commands WHERE command_id = 'c3'").get();
  assert.equal(row.status, 'APPLIED', 'gap receipt rewritten after retransmit');
  assert.equal(m.getState().tasks.t1.title, 'gap');
  assert.equal(m.getState().tasks.t1.note, 'n');
  db.close();
});

test('gap slot is not permanently blocked when the retry uses a different commandId', () => {
  const db = openDatabase(':memory:');
  const m1 = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });

  // seq3 先到:accepted=0 → 缺口回执落库(占用 (dev-1, 3) 槽位)
  m1.processIntegrationBatch([
    entry(1, 'dev-1', cmd('task.create', 't1', { title: 'a' }, 'c1', 1)),
    entry(2, 'dev-1', cmd('task.setTitle', 't1', { title: 'x' }, 'c3-old', 3)),
  ]);
  assert.equal(
    db.prepare("SELECT status FROM processed_commands WHERE command_id = 'c3-old'").get().status,
    'SEQUENCE_GAP',
  );

  // 模拟进程重启(新实例从 device_progress 恢复 accepted=1),
  // 客户端补齐 seq2 并重传 seq3 —— 但换了新 commandId(防御不规范客户端)
  const m2 = createMaterializer({ db, applyCommand, serverInstanceId: SERVER_ID });
  const result = m2.processIntegrationBatch([
    entry(3, 'dev-1', cmd('task.setNote', 't1', { note: 'n' }, 'c2', 2)),
    entry(4, 'dev-1', cmd('task.setTitle', 't1', { title: '新标题' }, 'c3-new', 3)),
  ]);

  assert.equal(result.receipts.find((r) => r.commandId === 'c3-new').status, 'APPLIED');
  const slot = db
    .prepare('SELECT command_id, status FROM processed_commands WHERE device_id = ? AND client_sequence = 3')
    .get('dev-1');
  assert.equal(slot.command_id, 'c3-new', 'stale GAP row replaced by the retransmitted command');
  assert.equal(slot.status, 'APPLIED');
  assert.equal(m2.getState().tasks.t1.title, '新标题');
  assert.equal(m2.getState().tasks.t1.note, 'n');
  db.close();
});
