import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openDatabase } from '../src/state/database.js';
import { insertReceipt } from '../src/state/receipts.js';
import { backupDatabase, verifyBackup } from '../src/state/backup.js';

test('online backup produces a consistent, openable copy', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'tplanner-backup-'));
  const dest = join(dir, 'tplanner.db.bak');
  try {
    const db = openDatabase(':memory:');
    insertReceipt(db, {
      commandId: 'cmd-1',
      batchId: 'batch-1',
      deviceId: 'dev-1',
      clientSequence: 1,
      brokerSequence: 100,
      commandType: 'task.create',
      aggregateId: 'task-1',
      status: 'APPLIED',
      errorCode: null,
      snapshotVersion: 1,
      resultJson: null,
      processedAt: 1700000000000,
    });

    await backupDatabase(db, dest);
    assert.ok(existsSync(dest));

    const check = verifyBackup(dest);
    assert.equal(check.version, 1);
    assert.equal(check.integrity, 'ok');
    assert.ok(check.tables >= 7);

    // 备份可独立打开并读到已提交数据
    const backup = openDatabase(dest);
    const row = backup
      .prepare("SELECT status FROM processed_commands WHERE command_id = 'cmd-1'")
      .get();
    assert.equal(row.status, 'APPLIED');
    backup.close();
    db.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('backup made before later writes does not see them (consistent point in time)', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'tplanner-backup-'));
  const dest = join(dir, 'tplanner.db.bak');
  try {
    const db = openDatabase(':memory:');
    await backupDatabase(db, dest);

    insertReceipt(db, {
      commandId: 'cmd-late',
      batchId: 'batch-1',
      deviceId: 'dev-1',
      clientSequence: 1,
      brokerSequence: 100,
      commandType: 'task.create',
      aggregateId: null,
      status: 'APPLIED',
      errorCode: null,
      snapshotVersion: null,
      resultJson: null,
      processedAt: 1,
    });

    const backup = openDatabase(dest);
    const count = backup
      .prepare("SELECT COUNT(*) AS c FROM processed_commands WHERE command_id = 'cmd-late'")
      .get().c;
    assert.equal(count, 0);
    backup.close();
    db.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
