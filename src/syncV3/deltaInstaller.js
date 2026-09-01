// delta-v1 下行安装器(见 docs/sync-v3.md §9.3/§9.4)。
//
// 铁律(与快照安装器完全同构):
//   - delta 只更新 Server Mirror;Displayed State 永远是
//     reduce(mirror, surviving pending commands),绝不直接改 UI 状态;
//   - 一个 /changes 响应的全部 commits + mirror + display + installed 指针 +
//     cursor 在同一个 IndexedDB transaction 内提交 —— cursor 绝不先于实体落地;
//   - 任何断链/未知 type/hash 失配/410 都 fail closed 到 snapshot fallback,
//     绝不猜测修补、绝不跳版本;
//   - 只有被终态回执 + snapshot/broker 证明覆盖的 outbox 命令才删除。
import { loadSyncMeta, META_KEY } from './syncMeta';
import { canonicalStateHash, projectCoveredOutbox } from './snapshotInstaller';
import { readJsonResponse } from './httpResponse';

const MIRROR_KEY = 'mirror';
const DISPLAY_KEY = 'display';

const MAP_KEY_BY_CHANGE_TYPE = {
    'task.put': 'tasks',
    'customList.put': 'customLists',
    'journal.put': 'journals',
    'goal.put': 'goals',
    'insight.put': 'insights',
};

export class DeltaRecoveryRequired extends Error {
    constructor(reason) {
        super(`delta recovery required: ${reason}`);
        this.name = 'DeltaRecoveryRequired';
        this.reason = reason;
    }
}

function isRecord(value) {
    return !!value && typeof value === 'object' && !Array.isArray(value);
}

function validateDeltaResponse(body, response) {
    if (!isRecord(body)
        || body.protocolVersion !== 3
        || body.deltaVersion !== 1
        || body.schemaVersion !== 3
        || typeof body.serverInstanceId !== 'string'
        || body.serverInstanceId === ''
        || typeof body.fromCursor !== 'string'
        || body.fromCursor === ''
        || typeof body.toCursor !== 'string'
        || body.toCursor === ''
        || !Number.isSafeInteger(body.headSnapshotVersion)
        || typeof body.hasMore !== 'boolean'
        || !Array.isArray(body.commits)) {
        throw new DeltaRecoveryRequired('DELTA_SCHEMA_UNSUPPORTED');
    }
    for (const commit of body.commits) {
        if (!isRecord(commit)
            || !Number.isSafeInteger(commit.snapshotVersion)
            || !Number.isSafeInteger(commit.parentVersion)
            || !Number.isSafeInteger(commit.brokerFromSequence)
            || !Number.isSafeInteger(commit.brokerToSequence)
            || typeof commit.stateHashAfter !== 'string'
            || !Array.isArray(commit.changes)) {
            throw new DeltaRecoveryRequired('DELTA_SCHEMA_UNSUPPORTED');
        }
    }
    return body;
}

/** 纯函数:把 authoritative changes 装到 mirror 上;未知 type 直接 fail closed。 */
export function applyChangesToMirror(mirror, changes) {
    let next = mirror;
    for (const change of changes) {
        const mapKey = MAP_KEY_BY_CHANGE_TYPE[change.type];
        if (!mapKey || typeof change.entityId !== 'string' || !isRecord(change.value)) {
            throw new DeltaRecoveryRequired(
                `UNKNOWN_DELTA_TYPE:${change?.type ?? 'missing'}`,
            );
        }
        next = { ...next, [mapKey]: { ...next[mapKey], [change.entityId]: change.value } };
    }
    return next;
}

export function createDeltaInstaller({ store, fetchFn, serverUrl, deltaEnabled = true } = {}) {
    function shouldUseDelta(capabilities, meta) {
        return deltaEnabled
            && Array.isArray(capabilities?.downlinkModes)
            && capabilities.downlinkModes.includes('delta-v1')
            && typeof meta?.cursor === 'string'
            && meta.cursor !== '';
    }

    async function fetchPage(cursor) {
        const url = `${serverUrl}/tplanner/v3/changes?cursor=${encodeURIComponent(cursor)}&maxCommits=100`;
        const res = await fetchFn(url, { headers: { 'cache-control': 'no-store' } });
        if (res.status === 410) {
            let body = null;
            try { body = await readJsonResponse(res, 'changes request'); } catch { /* fall through */ }
            throw new DeltaRecoveryRequired(body?.error ?? 'SYNC_CURSOR_EXPIRED');
        }
        if (!res.ok) throw new Error(`changes request failed: ${res.status}`);
        return validateDeltaResponse(await readJsonResponse(res, 'changes request'), res);
    }

    /**
     * 安装一页 commits。整页(含 cursor=toCursor)在一个 mutateMany 内原子提交;
     * 任何失败抛出前都还没有写入,cursor 保持旧值。
     */
    async function installPage(page) {
        const meta = await loadSyncMeta(store);

        // 1. 纯检查:版本链连续、重投幂等(<= installed 的 commit 直接跳过)
        let expectedParent = meta.installedSnapshotVersion;
        const applicable = [];
        for (const commit of page.commits) {
            if (commit.snapshotVersion <= meta.installedSnapshotVersion) continue;
            if (commit.parentVersion !== expectedParent) {
                throw new DeltaRecoveryRequired(
                    `DELTA_VERSION_GAP: commit ${commit.snapshotVersion} parent ${commit.parentVersion}, expected ${expectedParent}`,
                );
            }
            applicable.push(commit);
            expectedParent = commit.snapshotVersion;
        }
        if (applicable.length === 0) {
            // 整页都已被安装过(重投):只推进 cursor,不再动状态。
            const nextMeta = { ...meta, cursor: page.toCursor };
            await store.mutateMany({ setEntries: [[META_KEY, nextMeta]] });
            return { installed: false, skipped: true, version: meta.installedSnapshotVersion, cursor: page.toCursor, appliedCount: 0 };
        }
        if (meta.serverInstanceId && meta.serverInstanceId !== page.serverInstanceId) {
            const err = new Error('server instance changed; client must re-bootstrap');
            err.code = 'ERROR008';
            throw err;
        }

        // 2. 内存重建 + 逐 commit 校验 stateHashAfter(与 shadow validator 同语义)
        const mirror = await store.get(MIRROR_KEY);
        if (!mirror) throw new DeltaRecoveryRequired('NO_SERVER_MIRROR');
        let running = mirror;
        for (const commit of applicable) {
            running = applyChangesToMirror(running, commit.changes);
            const hash = await canonicalStateHash(running);
            if (hash !== commit.stateHashAfter) {
                throw new DeltaRecoveryRequired(`DELTA_HASH_MISMATCH:${commit.snapshotVersion}`);
            }
        }

        // 3. pending overlay:只有终态回执 + 证明覆盖的命令才删,其余原样重放
        const last = applicable[applicable.length - 1];
        const proof = {
            snapshotVersion: last.snapshotVersion,
            brokerToSequence: last.brokerToSequence,
        };
        const { display, deleteKeys } = await projectCoveredOutbox(store, running, proof);

        // 4. 原子提交:实体 mirror + display + installed 指针 + cursor 一起落地
        const nextMeta = {
            ...meta,
            installedSnapshotVersion: last.snapshotVersion,
            installedSnapshotHash: last.stateHashAfter,
            installedBrokerToSequence: last.brokerToSequence,
            serverInstanceId: page.serverInstanceId,
            cursor: page.toCursor,
        };
        await store.mutateMany({
            deleteKeys,
            setEntries: [
                [MIRROR_KEY, running],
                [DISPLAY_KEY, display],
                [META_KEY, nextMeta],
            ],
        });
        return {
            installed: true,
            skipped: false,
            version: last.snapshotVersion,
            cursor: page.toCursor,
            appliedCount: applicable.length,
        };
    }

    /**
     * cursor 驱动的同步循环。返回:
     *   { mode: 'delta', installed, version }       全部安装完成
     *   { mode: 'fallback', fallbackReason }        调用方必须走 full snapshot
     * 网络/存储错误原样抛出(不伪装成 fallback,下一轮重试)。
     */
    async function syncByCursor() {
        const meta = await loadSyncMeta(store);
        if (!shouldUseDelta({ downlinkModes: ['delta-v1'] }, meta)) {
            return { mode: 'fallback', fallbackReason: 'DELTA_NOT_ELIGIBLE' };
        }

        let cursor = meta.cursor;
        let applied = 0;
        let lastVersion = meta.installedSnapshotVersion;
        while (true) {
            let page;
            try {
                page = await fetchPage(cursor);
            } catch (err) {
                if (err instanceof DeltaRecoveryRequired) {
                    return { mode: 'fallback', fallbackReason: err.reason };
                }
                throw err;
            }
            if (page.fromCursor !== cursor) {
                return { mode: 'fallback', fallbackReason: 'DELTA_CURSOR_MISMATCH' };
            }
            let result;
            try {
                result = await installPage(page);
            } catch (err) {
                if (err instanceof DeltaRecoveryRequired) {
                    return { mode: 'fallback', fallbackReason: err.reason };
                }
                throw err;
            }
            if (result.installed) applied += result.appliedCount ?? 0;
            lastVersion = Math.max(lastVersion, result.version);
            if (!page.hasMore) {
                return { mode: 'delta', installed: applied > 0, version: lastVersion, appliedCommits: applied };
            }
            cursor = page.toCursor;
        }
    }

    return { shouldUseDelta, syncByCursor, applyChangesToMirror };
}
