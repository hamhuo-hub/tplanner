import { describe, test, expect } from 'vitest';
import { gzipSync, gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import canonicalize from 'canonicalize';
import { createMemoryKvStore } from './kvStore';
import { appendCommands, applyReceipts } from './commandOutbox';
import { loadSyncMeta, updateSyncMeta } from './syncMeta';
import { canonicalStateHash } from './snapshotInstaller';
import { applyChangesToMirror, createDeltaInstaller } from './deltaInstaller';
import { createSyncEngine } from './createSyncEngine';

function requireHash(input) {
    return createHash('sha256').update(input).digest('hex');
}

const EMPTY_STATE = { tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {} };

function makeTask(title) {
    return {
        title,
        note: '',
        completed: false,
        itemType: 'task',
        schedule: null,
        recurrence: null,
        alarm: { enabled: false, offsetMinutes: 0 },
        colorId: 0,
        location: { lat: null, lng: null },
        extras: {},
        listId: null,
        checklist: [],
        lifecycle: 'active',
        deletedAt: null,
    };
}

async function buildCommit({ snapshotVersion, parentVersion, brokerToSequence, changes, state }) {
    return {
        snapshotVersion,
        parentVersion,
        brokerFromSequence: brokerToSequence,
        brokerToSequence,
        stateHashAfter: await canonicalStateHash(state),
        changes,
    };
}

function buildPage({ fromCursor, toCursor, commits, hasMore = false, headSnapshotVersion }) {
    return {
        protocolVersion: 3,
        deltaVersion: 1,
        schemaVersion: 3,
        serverInstanceId: 'srv-test',
        fromCursor,
        toCursor,
        headSnapshotVersion,
        hasMore,
        commits,
    };
}

async function seedInstalledClient(store, { version = 5, cursor = 'cursor-5', mirror = EMPTY_STATE } = {}) {
    await store.set('mirror', mirror);
    await store.set('display', mirror);
    await updateSyncMeta(store, {
        serverInstanceId: 'srv-test',
        installedSnapshotVersion: version,
        installedBrokerToSequence: version,
        cursor,
    });
}

function stubJsonFetch(pagesByCursor, { status = 200 } = {}) {
    const calls = [];
    const fetchFn = async (url) => {
        calls.push(url);
        const cursor = new URLSearchParams(String(url).split('?')[1] ?? '').get('cursor');
        const page = pagesByCursor[cursor];
        if (!page) {
            if (status === 410) {
                return { status: 410, ok: false, json: async () => ({ error: 'CURSOR_EXPIRED', recovery: 'FULL_SNAPSHOT' }) };
            }
            throw new Error(`unexpected cursor: ${cursor}`);
        }
        return { status, ok: status < 400, json: async () => page };
    };
    return { calls, fetchFn };
}

describe('delta installer eligibility', () => {
    test('requires the capability, the local flag and a stored cursor', async () => {
        const store = createMemoryKvStore();
        const installer = createDeltaInstaller({ store, fetchFn: async () => { throw new Error('x'); }, serverUrl: '' });

        const caps = { downlinkModes: ['snapshot', 'delta-v1'] };
        const meta = await loadSyncMeta(store);
        expect(installer.shouldUseDelta(caps, meta)).toBe(false); // 无 cursor

        await updateSyncMeta(store, { cursor: 'cursor-1' });
        expect(installer.shouldUseDelta(caps, await loadSyncMeta(store))).toBe(true);

        expect(installer.shouldUseDelta({ downlinkModes: ['snapshot'] }, await loadSyncMeta(store))).toBe(false);

        const off = createDeltaInstaller({ store, fetchFn: async () => { throw new Error('x'); }, serverUrl: '', deltaEnabled: false });
        expect(off.shouldUseDelta(caps, await loadSyncMeta(store))).toBe(false);
    });
});

describe('delta installer', () => {
    test('installs a single commit atomically: mirror, display, pointers and cursor advance together', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const task = makeTask('新任务');
        const afterState = { tasks: { t1: task }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const commit = await buildCommit({
            snapshotVersion: 6,
            parentVersion: 5,
            brokerToSequence: 9,
            changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 9, value: task }],
            state: afterState,
        });
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const result = await installer.syncByCursor();
        expect(result).toEqual({ mode: 'delta', installed: true, version: 6, appliedCommits: 1 });
        expect(await store.get('mirror')).toEqual(afterState);
        expect((await store.get('display')).tasks.t1.title).toBe('新任务');
        const meta = await loadSyncMeta(store);
        expect(meta.cursor).toBe('cursor-6');
        expect(meta.installedSnapshotVersion).toBe(6);
        expect(meta.installedSnapshotHash).toBe(commit.stateHashAfter);
        expect(meta.installedBrokerToSequence).toBe(9);
    });

    test('installs multiple commits in strict order across one page', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const t1 = makeTask('a');
        const s6 = { tasks: { t1 }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const s7 = { ...s6, tasks: { ...s6.tasks, t2: makeTask('b') } };
        const c6 = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 10, changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 10, value: t1 }], state: s6 });
        const c7 = await buildCommit({ snapshotVersion: 7, parentVersion: 6, brokerToSequence: 11, changes: [{ type: 'task.put', entityId: 't2', entityBrokerSequence: 11, value: makeTask('b') }], state: s7 });
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-7', commits: [c6, c7], headSnapshotVersion: 7 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const result = await installer.syncByCursor();
        expect(result).toEqual({ mode: 'delta', installed: true, version: 7, appliedCommits: 2 });
        expect(await store.get('mirror')).toEqual(s7);
        expect((await loadSyncMeta(store)).installedSnapshotVersion).toBe(7);
    });

    test('empty commits advance version and cursor without touching the mirror', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const commit = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 6, changes: [], state: EMPTY_STATE });
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        await installer.syncByCursor();
        expect(await store.get('mirror')).toEqual(EMPTY_STATE);
        const meta = await loadSyncMeta(store);
        expect(meta.cursor).toBe('cursor-6');
        expect(meta.installedSnapshotVersion).toBe(6);
        expect(meta.installedSnapshotHash).toBe(commit.stateHashAfter);
    });

    test('an unknown change type fails closed to snapshot fallback without touching the cursor', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const page = buildPage({
            fromCursor: 'cursor-5',
            toCursor: 'cursor-6',
            commits: [{
                snapshotVersion: 6,
                parentVersion: 5,
                brokerFromSequence: 6,
                brokerToSequence: 6,
                stateHashAfter: 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
                changes: [{ type: 'task.title.patch', entityId: 't1', entityBrokerSequence: 6, value: { title: 'x' } }],
            }],
            headSnapshotVersion: 6,
        });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const result = await installer.syncByCursor();
        expect(result.mode).toBe('fallback');
        expect(result.fallbackReason).toBe('UNKNOWN_DELTA_TYPE:task.title.patch');
        expect((await loadSyncMeta(store)).cursor).toBe('cursor-5');
    });

    test('a parentVersion gap fails closed to snapshot fallback', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const page = buildPage({
            fromCursor: 'cursor-5',
            toCursor: 'cursor-6',
            commits: [{
                snapshotVersion: 6,
                parentVersion: 4, // 应为 5
                brokerFromSequence: 6,
                brokerToSequence: 6,
                stateHashAfter: 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
                changes: [],
            }],
            headSnapshotVersion: 6,
        });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const result = await installer.syncByCursor();
        expect(result.mode).toBe('fallback');
        expect(result.fallbackReason).toMatch(/DELTA_VERSION_GAP/);
    });

    test('a stateHashAfter mismatch fails closed to snapshot fallback', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const task = makeTask('a');
        const afterState = { tasks: { t1: task }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const commit = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 6, changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 6, value: task }], state: afterState });
        commit.stateHashAfter = 'sha256:0000000000000000000000000000000000000000000000000000000000000000';
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const result = await installer.syncByCursor();
        expect(result.mode).toBe('fallback');
        expect(result.fallbackReason).toBe('DELTA_HASH_MISMATCH:6');
        expect((await loadSyncMeta(store)).cursor).toBe('cursor-5');
        expect(await store.get('mirror')).toEqual(EMPTY_STATE);
    });

    test('a 410 response triggers fallback with the server-provided reason', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });
        const { fetchFn } = stubJsonFetch({}, { status: 410 });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const result = await installer.syncByCursor();
        expect(result.mode).toBe('fallback');
        expect(result.fallbackReason).toBe('CURSOR_EXPIRED');
    });

    test('a surviving pending command overlays the remote delta instead of being eaten by it', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        // 本地未确认的乐观改名
        const [pending] = await appendCommands(store, [
            { type: 'task.setTitle', aggregateId: 't1', arguments: { title: '本地乐观标题' } },
        ]);

        const canonical = makeTask('远端权威标题');
        const afterState = { tasks: { t1: canonical }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const commit = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 9, changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 9, value: canonical }], state: afterState });
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        await installer.syncByCursor();
        expect((await store.get('mirror')).tasks.t1.title).toBe('远端权威标题');
        expect((await store.get('display')).tasks.t1.title).toBe('本地乐观标题');
        expect(await store.get(`cmd:${pending.clientSequence}`)).toBeTruthy();
    });

    test('outbox commands covered by a terminal receipt are removed atomically with the install', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const [pending] = await appendCommands(store, [
            { type: 'task.setTitle', aggregateId: 't1', arguments: { title: 'confirmed' } },
        ]);
        await applyReceipts(store, [{
            commandId: pending.commandId,
            clientSequence: pending.clientSequence,
            brokerSequence: 8,
            status: 'APPLIED',
            snapshotVersion: 5,
        }]);

        const task = makeTask('confirmed');
        const afterState = { tasks: { t1: task }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const commit = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 9, changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 9, value: task }], state: afterState });
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        await installer.syncByCursor();
        expect(await store.get(`cmd:${pending.clientSequence}`)).toBeUndefined();
        expect((await store.get('display')).tasks.t1.title).toBe('confirmed');
    });

    test('a local transaction failure leaves the cursor and mirror untouched', async () => {
        const base = createMemoryKvStore();
        const store = { ...base, mutateMany: async () => { throw new Error('simulated IndexedDB failure'); } };
        await seedInstalledClient(store, { version: 5 });

        const task = makeTask('a');
        const afterState = { tasks: { t1: task }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const commit = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 6, changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 6, value: task }], state: afterState });
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        await expect(installer.syncByCursor()).rejects.toThrow(/IndexedDB/);
        const meta = await loadSyncMeta(store);
        expect(meta.cursor).toBe('cursor-5');
        expect(meta.installedSnapshotVersion).toBe(5);
        expect(await store.get('mirror')).toEqual(EMPTY_STATE);
    });

    test('re-delivering an already-installed commit is idempotent', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const task = makeTask('a');
        const afterState = { tasks: { t1: task }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const commit = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 6, changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 6, value: task }], state: afterState });
        const page = buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        // 第二次请求带着新 cursor,但服务器(异常地)把已安装的 commit 6 又发了一遍
        const stalePage = buildPage({ fromCursor: 'cursor-6', toCursor: 'cursor-6', commits: [commit], headSnapshotVersion: 6 });
        const { fetchFn } = stubJsonFetch({ 'cursor-5': page, 'cursor-6': stalePage });
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const first = await installer.syncByCursor();
        expect(first.installed).toBe(true);
        const second = await installer.syncByCursor();
        expect(second).toEqual({ mode: 'delta', installed: false, version: 6, appliedCommits: 0 });
        expect((await loadSyncMeta(store)).cursor).toBe('cursor-6');
        expect(await store.get('mirror')).toEqual(afterState);
    });

    test('pagination loops until hasMore is false', async () => {
        const store = createMemoryKvStore();
        await seedInstalledClient(store, { version: 5 });

        const s6 = { tasks: { t1: makeTask('a') }, customLists: {}, journals: {}, goals: {}, insights: {} };
        const s7 = { ...s6, tasks: { ...s6.tasks, t2: makeTask('b') } };
        const c6 = await buildCommit({ snapshotVersion: 6, parentVersion: 5, brokerToSequence: 6, changes: [{ type: 'task.put', entityId: 't1', entityBrokerSequence: 6, value: makeTask('a') }], state: s6 });
        const c7 = await buildCommit({ snapshotVersion: 7, parentVersion: 6, brokerToSequence: 7, changes: [{ type: 'task.put', entityId: 't2', entityBrokerSequence: 7, value: makeTask('b') }], state: s7 });
        const pages = {
            'cursor-5': buildPage({ fromCursor: 'cursor-5', toCursor: 'cursor-6', commits: [c6], hasMore: true, headSnapshotVersion: 7 }),
            'cursor-6': buildPage({ fromCursor: 'cursor-6', toCursor: 'cursor-7', commits: [c7], hasMore: false, headSnapshotVersion: 7 }),
        };
        const { calls, fetchFn } = stubJsonFetch(pages);
        const installer = createDeltaInstaller({ store, fetchFn, serverUrl: '' });

        const result = await installer.syncByCursor();
        expect(result).toEqual({ mode: 'delta', installed: true, version: 7, appliedCommits: 2 });
        expect(calls).toHaveLength(2);
        expect(await store.get('mirror')).toEqual(s7);
        expect((await loadSyncMeta(store)).cursor).toBe('cursor-7');
    });

    test('applyChangesToMirror is the only writer of mirror state', () => {
        const next = applyChangesToMirror(EMPTY_STATE, [
            { type: 'customList.put', entityId: 'l1', value: { title: '清单', lifecycle: 'active', deletedAt: null } },
        ]);
        expect(next.customLists.l1.title).toBe('清单');
        expect(() => applyChangesToMirror(EMPTY_STATE, [
            { type: 'task.delete', entityId: 't1', value: {} },
        ])).toThrow(/UNKNOWN_DELTA_TYPE/);
    });
});

describe('engine delta routing', () => {
    function buildSnapshot({ version = 8, state }) {
        const envelope = {
            snapshotSchemaVersion: 3,
            snapshotVersion: version,
            parentVersion: version - 1,
            serverInstanceId: 'srv-test',
            brokerFromSequence: 1,
            brokerToSequence: version,
            createdAt: '2026-08-19T00:00:00.000Z',
            state,
        };
        const canonical = canonicalize(state);
        const stateHash = `sha256:${requireHash(canonical)}`;
        const compressed = gzipSync(Buffer.from(JSON.stringify(envelope), 'utf8'));
        return {
            manifest: {
                snapshotVersion: version,
                parentVersion: version - 1,
                schemaVersion: 3,
                stateHash,
                compressedHash: `sha256:${requireHash(compressed)}`,
                encoding: 'gzip',
                compressedBytes: compressed.length,
                uncompressedBytes: Buffer.byteLength(JSON.stringify(envelope)),
                serverInstanceId: 'srv-test',
            },
            compressed,
        };
    }

    const nodeDecompress = (compressed) => new Uint8Array(gunzipSync(Buffer.from(compressed)));

    test('syncNow falls back to a full snapshot when /changes returns 410', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ version: 8, state: { tasks: { t1: makeTask('snapshot truth') }, customLists: {}, journals: {}, goals: {}, insights: {} } });
        await store.set('mirror', EMPTY_STATE);
        await store.set('display', EMPTY_STATE);
        await updateSyncMeta(store, {
            serverInstanceId: 'srv-test',
            installedSnapshotVersion: 7,
            installedSnapshotHash: null,
            installedSnapshotCompressedHash: null,
            installedBrokerToSequence: 7,
            cursor: 'cursor-7',
        });

        const fetchFn = async (url) => {
            if (url.includes('/capabilities')) {
                return { status: 200, ok: true, json: async () => ({ softwareVersion: '8.0.0', protocolVersion: 3, schemaVersion: 3, serverInstanceId: 'srv-test', downlinkModes: ['snapshot', 'delta-v1'] }) };
            }
            if (url.includes('/changes')) {
                return { status: 410, ok: false, json: async () => ({ error: 'CURSOR_EXPIRED', recovery: 'FULL_SNAPSHOT' }) };
            }
            if (url.includes('/receipts')) {
                return { status: 200, ok: true, json: async () => ({ acceptedThrough: 0, results: [] }) };
            }
            if (url.endsWith('/snapshots/latest')) {
                return { status: 200, ok: true, json: async () => snap.manifest };
            }
            if (url.includes('/snapshots/8')) {
                return { status: 200, ok: true, arrayBuffer: async () => snap.compressed.buffer.slice(snap.compressed.byteOffset, snap.compressed.byteOffset + snap.compressed.byteLength) };
            }
            if (url.includes('/snapshot-acks')) {
                return { status: 202, ok: true };
            }
            throw new Error(`unexpected fetch: ${url}`);
        };

        const engine = await createSyncEngine({ store, serverUrl: 'https://sync.example', fetchFn, decompress: nodeDecompress });
        const result = await engine.syncNow();

        expect(result.mode).toBe('snapshot-fallback');
        expect(result.fallbackReason).toBe('CURSOR_EXPIRED');
        expect(result).toMatchObject({ installed: true, version: 8 });
        expect((await store.get('mirror')).tasks.t1.title).toBe('snapshot truth');
    });

    test('first bootstrap has no cursor and still uses the snapshot path', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ version: 1, state: EMPTY_STATE });
        const fetchFn = async (url) => {
            if (url.includes('/capabilities')) {
                return { status: 200, ok: true, json: async () => ({ softwareVersion: '8.0.0', protocolVersion: 3, schemaVersion: 3, serverInstanceId: 'srv-test', downlinkModes: ['snapshot', 'delta-v1'] }) };
            }
            if (url.includes('/receipts')) {
                return { status: 200, ok: true, json: async () => ({ acceptedThrough: 0, results: [] }) };
            }
            if (url.endsWith('/snapshots/latest')) {
                return { status: 200, ok: true, json: async () => snap.manifest };
            }
            if (url.includes('/snapshots/1')) {
                return { status: 200, ok: true, arrayBuffer: async () => snap.compressed.buffer.slice(snap.compressed.byteOffset, snap.compressed.byteOffset + snap.compressed.byteLength) };
            }
            throw new Error(`unexpected fetch: ${url}`);
        };

        const engine = await createSyncEngine({ store, serverUrl: 'https://sync.example', fetchFn, decompress: nodeDecompress });
        const result = await engine.syncNow();
        expect(result.mode).toBe('snapshot');
        expect(result).toMatchObject({ installed: true, version: 1 });
    });
});
