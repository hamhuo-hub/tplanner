// SQLite 在线备份(见 docs/sync-v3.md §27)。
// better-sqlite3 的 backup API 走 SQLite Online Backup,写入期间数据库保持可用,
// 产出文件是完整一致的快照,可直接被 openDatabase 打开。
import { createHash } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import {
  mkdir,
  readFile,
  readdir,
  rename,
  stat,
  unlink,
  writeFile,
} from 'node:fs/promises';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { createGzip } from 'node:zlib';
import Database from 'better-sqlite3';

export async function backupDatabase(db, destPath) {
  await db.backup(destPath);
  return destPath;
}

export function verifyBackup(destPath) {
  // Verification must not run application migrations or change journal mode:
  // this artifact is the exact pre-migration recovery point.
  const backup = new Database(destPath, { readonly: true, fileMustExist: true });
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

function backupStamp(timestamp) {
  return new Date(timestamp)
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}Z$/, 'Z');
}

export async function writeCompressedBackup(
  db,
  backupDir,
  {
    now = () => Date.now(),
  } = {},
) {
  const createdAt = now();
  const base = `tplanner-${backupStamp(createdAt)}`;
  const tempDb = join(backupDir, `.${base}.db.tmp`);
  const tempGzip = join(backupDir, `.${base}.db.gz.tmp`);
  const tempManifest = join(backupDir, `.${base}.json.tmp`);
  const gzipPath = join(backupDir, `${base}.db.gz`);
  const manifestPath = join(backupDir, `${base}.json`);

  await mkdir(backupDir, { recursive: true, mode: 0o700 });
  try {
    await backupDatabase(db, tempDb);
    const verification = verifyBackup(tempDb);
    if (verification.integrity !== 'ok') {
      throw new Error(`SQLite backup integrity check failed: ${verification.integrity}`);
    }

    await pipeline(
      createReadStream(tempDb),
      createGzip({ level: 9 }),
      createWriteStream(tempGzip, { mode: 0o600 }),
    );
    const compressed = await readFile(tempGzip);
    const manifest = {
      createdAt: new Date(createdAt).toISOString(),
      file: `${base}.db.gz`,
      sha256: createHash('sha256').update(compressed).digest('hex'),
      compressedBytes: compressed.length,
      sqliteUserVersion: verification.version,
      sqliteIntegrity: verification.integrity,
      sqliteTables: verification.tables,
    };
    await writeFile(tempManifest, `${JSON.stringify(manifest)}\n`, { mode: 0o600 });

    // The manifest is renamed last and acts as the completion marker.
    await rename(tempGzip, gzipPath);
    await rename(tempManifest, manifestPath);
    return { gzipPath, manifestPath, manifest };
  } finally {
    for (const path of [tempDb, `${tempDb}-wal`, `${tempDb}-shm`, tempGzip, tempManifest]) {
      await unlink(path).catch((error) => {
        if (error.code !== 'ENOENT') throw error;
      });
    }
  }
}

/** Keep every hourly backup for 24h, then the newest per UTC day for 30 days. */
export async function pruneBackups(
  backupDir,
  {
    now = () => Date.now(),
  } = {},
) {
  const names = await readdir(backupDir).catch((error) => {
    if (error.code === 'ENOENT') return [];
    throw error;
  });
  const candidates = [];
  for (const name of names) {
    const match = /^tplanner-(\d{8}T\d{6}Z)\.db\.gz$/.exec(name);
    if (!match) continue;
    const path = join(backupDir, name);
    const info = await stat(path);
    candidates.push({ name, path, mtimeMs: info.mtimeMs });
  }
  candidates.sort((a, b) => b.mtimeMs - a.mtimeMs);

  const keptDays = new Set();
  const removed = [];
  const current = now();
  for (const backup of candidates) {
    const ageMs = Math.max(0, current - backup.mtimeMs);
    let keep = ageMs <= 24 * 60 * 60 * 1000;
    if (!keep && ageMs <= 30 * 24 * 60 * 60 * 1000) {
      const day = new Date(backup.mtimeMs).toISOString().slice(0, 10);
      keep = !keptDays.has(day);
      if (keep) keptDays.add(day);
    }
    if (keep) continue;

    const manifest = backup.path.replace(/\.db\.gz$/, '.json');
    await unlink(backup.path);
    await unlink(manifest).catch((error) => {
      if (error.code !== 'ENOENT') throw error;
    });
    removed.push(backup.name);
  }
  return removed;
}
