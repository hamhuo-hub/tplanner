// SQLite 打开与迁移:文件名 NNN-name.sql 按序应用,user_version 记录当前版本。
// 每次迁移在单个事务内执行,expand-only(不回退)。
import Database from 'better-sqlite3';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const MIGRATIONS_DIR = new URL('./migrations/', import.meta.url);

export function openDatabase(dbPath) {
  const db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  db.pragma('synchronous = FULL'); // ACK 语义:已提交 = 已落盘
  db.pragma('foreign_keys = ON');
  db.pragma('busy_timeout = 5000');
  migrate(db);
  return db;
}

export function migrate(db) {
  const files = readdirSync(fileURLToPath(MIGRATIONS_DIR))
    .filter((f) => f.endsWith('.sql'))
    .sort();

  const current = db.pragma('user_version', { simple: true });
  const applyAll = db.transaction((sql, version) => {
    db.exec(sql);
    db.pragma(`user_version = ${version}`);
  });

  for (const file of files) {
    const version = Number(file.split('-')[0]);
    if (!Number.isInteger(version) || version <= 0) {
      throw new Error(`bad migration filename: ${file}`);
    }
    if (version > current) {
      const sql = readFileSync(new URL(file, MIGRATIONS_DIR), 'utf8');
      applyAll(sql, version);
    }
  }
}
