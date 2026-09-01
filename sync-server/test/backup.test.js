import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdtempSync, rmSync, existsSync, readFileSync, writeFileSync, utimesSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openDatabase } from '../src/state/database.js';
import { insertReceipt } from '../src/state/receipts.js';
import {
  backupDatabase, pruneBackups, verifyBackup, writeCompressedBackup,
} from '../src/state/backup.js';

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

test('compressed backup is verified and accompanied by a SHA-256 manifest', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'tplanner-backup-'));
  try {
    const db = openDatabase(':memory:');
    const result = await writeCompressedBackup(db, dir, { now: () => 1_800_000_000_000 });
    assert.ok(existsSync(result.gzipPath));
    assert.ok(existsSync(result.manifestPath));
    assert.equal(result.manifest.sqliteIntegrity, 'ok');
    const bytes = readFileSync(result.gzipPath);
    assert.equal(result.manifest.sha256, createHash('sha256').update(bytes).digest('hex'));
    assert.deepEqual(JSON.parse(readFileSync(result.manifestPath, 'utf8')), result.manifest);
    db.close();
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('retention keeps 24h hourly, then one per UTC day for 30 days', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'tplanner-backup-retention-'));
  const now = Date.parse('2026-09-01T12:00:00.000Z');
  const add = (stamp, ageHours) => {
    const gzip = join(dir, `tplanner-${stamp}.db.gz`);
    const manifest = join(dir, `tplanner-${stamp}.json`);
    writeFileSync(gzip, 'gzip');
    writeFileSync(manifest, '{}');
    const modified = new Date(now - ageHours * 60 * 60 * 1000);
    utimesSync(gzip, modified, modified);
    utimesSync(manifest, modified, modified);
    return { gzip, manifest };
  };

  try {
    const hourly = add('20260901T110000Z', 1);
    const dailyNewest = add('20260831T100000Z', 26);
    const dailyOlder = add('20260831T090000Z', 27);
    const expired = add('20260731T100000Z', 32 * 24);

    const removed = await pruneBackups(dir, { now: () => now });

    assert.ok(existsSync(hourly.gzip));
    assert.ok(existsSync(dailyNewest.gzip));
    assert.equal(existsSync(dailyOlder.gzip), false);
    assert.equal(existsSync(dailyOlder.manifest), false);
    assert.equal(existsSync(expired.gzip), false);
    assert.deepEqual(removed.sort(), [
      'tplanner-20260731T100000Z.db.gz',
      'tplanner-20260831T090000Z.db.gz',
    ]);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
