// deploy/backup.sh 的端到端 smoke test(见 docs/ops-runbook.md §3)。
//
// 直接执行真实的备份脚本(路径用环境变量指到临时目录),覆盖:
//   - cursor-secret 存在 → config-latest.tar.gz 必须包含它;
//   - cursor-secret 不存在 → 脚本仍成功,归档不含它。
// 这防的是"GNU tar 不能向已压缩的 .tar.gz 追加成员"一类回归 —— 那会让
// API 首次启动生成密钥后,每小时备份与下一次 install.sh 全部失败。
//
// 无 bash 的环境(部分 Windows 开发机)自动跳过;CI(ubuntu)一定执行。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { openDatabase } from '../src/state/database.js';
import { ensureBootstrapSnapshot } from '../src/materializer/materializer.js';

const SYNC_SERVER_DIR = fileURLToPath(new URL('../', import.meta.url));
const BASH = ['bash', 'C:/Program Files/Git/bin/bash.exe', 'C:/Program Files/Git/usr/bin/bash.exe']
  .find((candidate) => spawnSync(candidate, ['-c', 'exit 0'], { stdio: 'ignore' }).status === 0);
// Windows 下 MSYS tar 会把 "C:/..." 路径当成远程主机(GNU tar -C 的 host:dir 语法),
// 而 native node 又需要 win32 路径 —— 同一个 DATA_DIR 无法同时满足两者。
// 本测试针对生产(Linux)shell 语义,win32 本地运行跳过,由 CI(ubuntu)强制执行。
const skip = process.platform === 'win32' || BASH === undefined
  ? 'smoke test runs on POSIX hosts (CI enforces it on ubuntu)'
  : false;

// MSYS/Git Bash 会把反斜杠路径搞坏;传给脚本的环境变量统一用正斜杠。
const forward = (path) => path.replaceAll('\\', '/');

function prepareDataDir({ withSecret }) {
  const dataDir = mkdtempSync(join(tmpdir(), 'tplanner-backup-smoke-'));

  // 应用根目录夹具:备份脚本只从这里 tar nats 配置与凭据(nats.creds 由
  // install.sh 在部署时生成,仓库本身不含)。
  const appRoot = join(dataDir, 'app-root');
  const deployDir = join(appRoot, 'sync-server', 'deploy');
  mkdirSync(deployDir, { recursive: true });
  copyFileSync(join(SYNC_SERVER_DIR, 'deploy', 'nats-server.conf'), join(deployDir, 'nats-server.conf'));
  writeFileSync(join(deployDir, 'nats.creds'), 'tplanner:smoke-test-password\n', { mode: 0o600 });

  // 真实迁移后的库(bootstrap 快照)充当"迁移前恢复点"的角色。
  const stateDir = join(dataDir, 'state');
  mkdirSync(stateDir, { recursive: true });
  const db = openDatabase(join(stateDir, 'tplanner.db'));
  ensureBootstrapSnapshot(db, { serverInstanceId: 'srv-backup-smoke' });
  db.close();

  if (withSecret) {
    writeFileSync(join(stateDir, 'tplanner.db.cursor-secret'), 's'.repeat(38), { mode: 0o600 });
  }
  return { dataDir, appRoot };
}

function runBackupScript(dataDir, appRoot) {
  const result = spawnSync(BASH, [forward(join(SYNC_SERVER_DIR, 'deploy', 'backup.sh'))], {
    env: {
      ...process.env,
      TPLANNER_DATA_DIR: forward(dataDir),
      TPLANNER_APP_ROOT: forward(appRoot),
      TPLANNER_APP_DIR: forward(SYNC_SERVER_DIR),
      TPLANNER_NODE_BIN: forward(process.execPath),
    },
    encoding: 'utf8',
  });
  const backupDir = join(dataDir, 'backups');
  const configTar = join(backupDir, 'config-latest.tar.gz');
  const members = spawnSync(BASH, ['-c', 'tar -tzf "$1"', 'tar', forward(configTar)], { encoding: 'utf8' }).stdout;
  return { result, backupDir, configTar, members };
}

test('backup.sh packs the cursor secret together with config (with-secret smoke)', { skip }, () => {
  const { dataDir, appRoot } = prepareDataDir({ withSecret: true });
  try {
    const { result, backupDir, configTar, members } = runBackupScript(dataDir, appRoot);
    assert.equal(result.status, 0, `backup.sh failed:\n${result.stdout}\n${result.stderr}`);
    assert.ok(existsSync(configTar), 'config-latest.tar.gz must exist');
    assert.ok(members.includes('tplanner.db.cursor-secret'), `archive members:\n${members}`);
    assert.ok(
      readdirSync(backupDir).some((name) => name.endsWith('.db.gz')),
      'a verified SQLite backup must be written',
    );
    assert.ok(
      readdirSync(backupDir).some((name) => name.endsWith('.json')),
      'a backup manifest must be written',
    );
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});

test('backup.sh succeeds without a cursor secret (no-secret smoke)', { skip }, () => {
  const { dataDir, appRoot } = prepareDataDir({ withSecret: false });
  try {
    const { result, configTar, members } = runBackupScript(dataDir, appRoot);
    assert.equal(result.status, 0, `backup.sh failed:\n${result.stdout}\n${result.stderr}`);
    assert.ok(existsSync(configTar), 'config-latest.tar.gz must exist');
    assert.ok(!members.includes('tplanner.db.cursor-secret'), `archive members:\n${members}`);
  } finally {
    rmSync(dataDir, { recursive: true, force: true });
  }
});
