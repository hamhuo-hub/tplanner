// Shadow reconstruction validator(见 docs/sync-v3.md §9.1)。
//
// 它只证明 journal 是否可信,绝不参与制造可信结果:发现任何断链、篡改、
// parent/schema 异常一律 fail closed(抛 JournalValidationError),绝不
// 重写 commit、重生成 delta 或跳过版本。
//
// 验证语义:
//   state(min) → applyJournalCommit(v) → 每个 checkpoint 的 canonical
//   stateHash 必须同时等于 change_commits.state_hash_after 与
//   snapshots.state_hash,即 Snapshot(N) + Commit(N+1) == Snapshot(N+1)。
//
// validateJournalRange:从快照 checkpoint 出发的全链重建(纯核心)。
// validateJournalTail:  从已验证的 baseState 出发只验新增尾部。
//   (尾部不重验 base 快照行,这是相对全链验证的已知取舍;启动全链 + 定时
//    尾部是 shadow 运行时的组合。)
import { gunzipSync } from 'node:zlib';
import { emptyState } from '../materializer/reducer.js';
import { canonicalStateHash } from '../materializer/snapshot.js';
import { applyJournalCommit, getJournalMeta, loadJournalCommit } from './journal.js';

export class JournalValidationError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'JournalValidationError';
    this.code = code;
  }
}

function assertValidRange(fromSnapshotVersion, toSnapshotVersion) {
  if (
    !Number.isSafeInteger(fromSnapshotVersion)
    || fromSnapshotVersion < 0
    || !Number.isSafeInteger(toSnapshotVersion)
    || toSnapshotVersion < fromSnapshotVersion
  ) {
    throw new Error(`invalid journal range: ${fromSnapshotVersion}..${toSnapshotVersion}`);
  }
}

function loadBaseState(db, version) {
  if (version === 0) {
    const state = emptyState();
    return { state, hash: canonicalStateHash(state) };
  }
  const row = db
    .prepare('SELECT state_hash, compressed_payload FROM snapshots WHERE version = ?')
    .get(version);
  if (!row) {
    throw new JournalValidationError(
      'JOURNAL_BASE_SNAPSHOT_MISSING',
      `base snapshot ${version} is missing; the chain cannot start`,
    );
  }
  const envelope = JSON.parse(gunzipSync(row.compressed_payload).toString('utf8'));
  const state = envelope.state;
  const hash = canonicalStateHash(state);
  if (hash !== row.state_hash) {
    throw new JournalValidationError(
      'SNAPSHOT_HASH_MISMATCH',
      `base snapshot ${version} payload hash does not match its stored state_hash`,
    );
  }
  return { state, hash };
}

function validateStep(db, { version, state }) {
  const commit = loadJournalCommit(db, version);
  if (!commit) {
    throw new JournalValidationError(
      'JOURNAL_COMMIT_MISSING',
      `journal commit ${version} is missing (gap in version coverage)`,
    );
  }
  if (commit.parentVersion !== version - 1) {
    throw new JournalValidationError(
      'JOURNAL_PARENT_MISMATCH',
      `commit ${version} has parentVersion ${commit.parentVersion}, expected ${version - 1}`,
    );
  }
  let next;
  try {
    next = applyJournalCommit(state, commit);
  } catch (err) {
    throw new JournalValidationError(
      'JOURNAL_CHANGE_INVALID',
      `commit ${version} carries an uninstallable change: ${err.message}`,
    );
  }
  const hash = canonicalStateHash(next);
  if (commit.stateHashAfter !== hash) {
    throw new JournalValidationError(
      'JOURNAL_HASH_MISMATCH',
      `commit ${version} stateHashAfter does not match the reconstructed state`,
    );
  }
  const snapshot = db
    .prepare('SELECT state_hash FROM snapshots WHERE version = ?')
    .get(version);
  if (!snapshot || snapshot.state_hash !== hash) {
    throw new JournalValidationError(
      'SNAPSHOT_HASH_MISMATCH',
      `snapshot ${version} state hash does not match the reconstructed state`,
    );
  }
  return { state: next, hash };
}

function summary({ fromSnapshotVersion, toSnapshotVersion, state, hash }) {
  return {
    ok: true,
    fromSnapshotVersion,
    toSnapshotVersion,
    validatedCommits: toSnapshotVersion - fromSnapshotVersion,
    headStateHash: hash,
    headState: state,
  };
}

/**
 * 从 Snapshot(fromSnapshotVersion) 出发,连续应用 journal commits 重建到
 * toSnapshotVersion,并逐 checkpoint 比对 hash。fromSnapshotVersion = 0
 * 表示从空状态出发(新装库的 bootstrap 场景)。
 */
export function validateJournalRange(db, { fromSnapshotVersion, toSnapshotVersion }) {
  assertValidRange(fromSnapshotVersion, toSnapshotVersion);
  const { state: baseState, hash: baseHash } = loadBaseState(db, fromSnapshotVersion);
  let current = baseState;
  let currentHash = baseHash;
  for (let version = fromSnapshotVersion + 1; version <= toSnapshotVersion; version += 1) {
    ({ state: current, hash: currentHash } = validateStep(db, { version, state: current }));
  }
  return summary({ fromSnapshotVersion, toSnapshotVersion, state: current, hash: currentHash });
}

/**
 * 尾部验证:baseState 必须来自先前一次通过验证的重建结果。父版本连续性
 * 与 checkpoint hash 仍逐版本检查;唯一不重验的是 base 快照行本身。
 */
export function validateJournalTail(db, { fromSnapshotVersion, toSnapshotVersion, baseState }) {
  assertValidRange(fromSnapshotVersion, toSnapshotVersion);
  if (typeof baseState !== 'object' || baseState === null || Array.isArray(baseState)) {
    throw new Error('validateJournalTail requires the previously validated baseState');
  }
  let current = baseState;
  let currentHash = canonicalStateHash(baseState);
  for (let version = fromSnapshotVersion + 1; version <= toSnapshotVersion; version += 1) {
    ({ state: current, hash: currentHash } = validateStep(db, { version, state: current }));
  }
  return summary({ fromSnapshotVersion, toSnapshotVersion, state: current, hash: currentHash });
}

/**
 * 默认入口:from = sync_journal_meta.min_snapshot_version,to = latest snapshot。
 * head <= from(尚无新 commit)时返回 validatedCommits = 0。
 */
export function validateJournalHead(db) {
  const meta = getJournalMeta(db);
  if (!meta) throw new Error('sync_journal_meta is not initialized');
  const head = db
    .prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1')
    .get()?.version ?? 0;
  return validateJournalRange(db, {
    fromSnapshotVersion: meta.minSnapshotVersion,
    toSnapshotVersion: head,
  });
}
