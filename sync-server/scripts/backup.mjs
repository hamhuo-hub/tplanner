#!/usr/bin/env node
import { stat } from 'node:fs/promises';
import Database from 'better-sqlite3';
import { pruneBackups, writeCompressedBackup } from '../src/state/backup.js';

const dbPath = process.env.TPLANNER_DB_PATH || '/var/lib/tplanner-sync/state/tplanner.db';
const backupDir = process.env.TPLANNER_BACKUP_DIR || '/var/lib/tplanner-sync/backups';
const source = await stat(dbPath).catch((error) => {
  if (error.code === 'ENOENT') throw new Error(`refusing to create a backup from missing database: ${dbPath}`);
  throw error;
});
if (!source.isFile() || source.size === 0) {
  throw new Error(`refusing to back up an invalid database file: ${dbPath}`);
}
// Open the source read-only and bypass application migrations: this backup is
// the recovery point immediately before any release migration runs.
const db = new Database(dbPath, { readonly: true, fileMustExist: true });

try {
  const backup = await writeCompressedBackup(db, backupDir);
  const removed = await pruneBackups(backupDir);
  console.log(JSON.stringify({
    backup: backup.manifest,
    manifestPath: backup.manifestPath,
    removed,
  }));
} finally {
  db.close();
}
