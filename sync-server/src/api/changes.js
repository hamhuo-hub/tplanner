// /tplanner/v3/changes(delta-v1 下行,见 docs/sync-v3.md §9.3/§9.4/§11)。
//
// 流程:cursor → validate → 读 cursor.snapshotVersion 之后的连续 commits →
// 按 commit 分页(绝不把一个 commit 切到两页)→ 返回 fromCursor/toCursor/
// hasMore。任何 410 都只表达一件事:此同步位置不可再用,客户端走
// full snapshot;服务端绝不替客户端修补。
import { getJournalMeta, loadJournalCommitRange, pruneJournal, toWireCommit } from '../state/journal.js';
import { issueCursor, validateCursor } from '../state/cursor.js';

export const DEFAULT_MAX_COMMITS = 100;
export const MAX_COMMITS_CAP = 100;
export const DEFAULT_RETENTION_KEEP_COMMITS = 100_000;
export const DEFAULT_RETENTION_KEEP_AGE_MS = 30 * 24 * 3_600_000;

export function createChangesService({
  db,
  serverInstanceId,
  cursorSecret,
  schemaVersion = 3,
  deltaVersion = 1,
  principal = 'default',
  enabled = true,
  retention = {},
  maxCommitsDefault = DEFAULT_MAX_COMMITS,
  maxCommitsCap = MAX_COMMITS_CAP,
  metrics = null,
  now = Date.now,
}) {
  const keepCommits = Number.isSafeInteger(retention.keepCommits) && retention.keepCommits >= 1
    ? retention.keepCommits
    : DEFAULT_RETENTION_KEEP_COMMITS;
  const keepAgeMs = Number.isSafeInteger(retention.keepAgeMs) && retention.keepAgeMs >= 0
    ? retention.keepAgeMs
    : DEFAULT_RETENTION_KEEP_AGE_MS;

  const headSnapshotVersion = () =>
    db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get()?.version ?? 0;

  const snapshotBrokerToSequence = (version) =>
    db.prepare('SELECT broker_to_sequence FROM snapshots WHERE version = ?')
      .get(version)?.broker_to_sequence ?? null;

  const journalMeta = () => getJournalMeta(db) ?? { journalEpoch: 'uninitialized', minSnapshotVersion: 0 };

  /** 服务端在 commit 边界签发 cursor(正常路径的坐标永远来自已提交的 commit)。 */
  function issueCursorAt({ snapshotVersion, brokerToSequence }) {
    return issueCursor({
      serverInstanceId,
      journalEpoch: journalMeta().journalEpoch,
      snapshotVersion,
      brokerToSequence,
      schemaVersion,
      deltaVersion,
      principal,
      issuedAt: now(),
      secret: cursorSecret,
    });
  }

  function capabilities() {
    return {
      downlinkModes: enabled ? ['snapshot', 'delta-v1'] : ['snapshot'],
      delta: {
        version: deltaVersion,
        maxCommits: maxCommitsDefault,
        journalEpoch: journalMeta().journalEpoch,
        minSnapshotVersion: journalMeta().minSnapshotVersion,
        headSnapshotVersion: headSnapshotVersion(),
      },
    };
  }

  function handleChanges(rawCursor, requestedMaxCommits) {
    const startedAt = now();
    const head = headSnapshotVersion();

    if (!enabled) {
      metrics?.increment('snapshot_fallback_total:DELTA_DISABLED');
      return {
        status: 410,
        body: { error: 'DELTA_DISABLED', recovery: 'FULL_SNAPSHOT', latestSnapshotVersion: head },
      };
    }
    if (typeof rawCursor !== 'string' || rawCursor === '') {
      return { status: 400, body: { error: 'CURSOR_REQUIRED' } };
    }
    let pageSize = maxCommitsDefault;
    if (requestedMaxCommits !== undefined) {
      if (!Number.isInteger(requestedMaxCommits) || requestedMaxCommits < 1) {
        return { status: 400, body: { error: 'BAD_MAX_COMMITS' } };
      }
      pageSize = Math.min(requestedMaxCommits, maxCommitsCap);
    }

    // Retention 先跑:恰好被剪掉的老 cursor 因此得到 410,而不是读到中间断链。
    pruneJournal(db, { keepCommits, keepAgeMs, now });

    const validation = validateCursor(rawCursor, {
      secret: cursorSecret,
      serverInstanceId,
      journalEpoch: journalMeta().journalEpoch,
      schemaVersion,
      deltaVersion,
      principal,
      minSnapshotVersion: journalMeta().minSnapshotVersion,
      headSnapshotVersion: headSnapshotVersion(),
      snapshotBrokerToSequence,
      now,
    });
    if (!validation.ok) {
      if (validation.status === 410) {
        metrics?.increment(`snapshot_fallback_total:${validation.code}`);
        return {
          status: 410,
          body: {
            error: validation.code,
            recovery: 'FULL_SNAPSHOT',
            latestSnapshotVersion: headSnapshotVersion(),
          },
        };
      }
      return { status: validation.status, body: { error: validation.code } };
    }

    metrics?.setGauge('cursor_lag_versions', Math.max(0, headSnapshotVersion() - validation.token.snapshotVersion));
    metrics?.setGauge(
      'cursor_age_seconds',
      Math.max(0, Math.floor((now() - (validation.token.issuedAt ?? now())) / 1_000)),
    );

    const commits = loadJournalCommitRange(db, {
      fromSnapshotVersion: validation.token.snapshotVersion,
      limit: pageSize,
    });
    const last = commits[commits.length - 1];
    const currentHead = headSnapshotVersion();
    const hasMore = commits.length === pageSize && last !== undefined && currentHead > last.snapshotVersion;

    // toCursor 永远等于最后一个完整返回的 commit;没有新 commit 时原样回传,
    // 让无变化轮询保持 cursor 稳定。
    const toCursor = last
      ? issueCursorAt({ snapshotVersion: last.snapshotVersion, brokerToSequence: last.brokerToSequence })
      : rawCursor;

    const body = {
      protocolVersion: 3,
      deltaVersion,
      schemaVersion,
      serverInstanceId,
      fromCursor: rawCursor,
      toCursor,
      headSnapshotVersion: currentHead,
      hasMore,
      commits: commits.map(toWireCommit),
    };
    metrics?.increment('delta_requests_total');
    metrics?.increment('delta_commits_total', commits.length);
    metrics?.increment('delta_response_bytes', JSON.stringify(body).length);
    metrics?.increment('delta_request_ms_total', now() - startedAt);
    return { status: 200, body };
  }

  return { handleChanges, capabilities, issueCursorAt };
}
