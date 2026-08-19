// SQLite 在线备份(见 docs/sync-v3.md §27)。
// better-sqlite3 的 backup API 走 SQLite Online Backup,写入期间数据库保持可用,
// 产出文件是完整一致的快照,可直接被 openDatabase 打开。
import { openDatabase } from './database.js';

export async function backupDatabase(db, destPath) {
  await db.backup(destPath);
  return destPath;
}

export function verifyBackup(destPath) {
  const backup = openDatabase(destPath);
  try {
    const version = backup.pragma('user_version', { simple: true });
    const integrity = backup.pragma('integrity_check', { simple: true });
    const tables = backup
      .prepare("SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table'")
      .get().c;
    return { version, integrity, tables };
  } finally {
    backup.close();
  }
}
