import { describe, test, expect, vi } from 'vitest';
import { gzipSync, gunzipSync } from 'node:zlib';
import canonicalize from 'canonicalize';
import { createMemoryKvStore } from './kvStore';
import { applyReceipts, appendCommands, listCommands, markUploaded } from './commandOutbox';
import { loadSyncMeta } from './syncMeta';
import { createSnapshotInstaller, sha256Hex } from './snapshotInstaller';

function buildSnapshot({ version = 7, parent = 6, brokerToSequence = 2, state }) {
    const envelope = {
        snapshotSchemaVersion: 3,
        snapshotVersion: version,
        parentVersion: parent,
        serverInstanceId: 'srv-test',
        brokerFromSequence: 1,
        brokerToSequence,
        createdAt: '2026-08-19T00:00:00.000Z',
        state,
    };
    const canonical = canonicalize(state);
    const stateHash = `sha256:${requireHash(canonical)}`;
    const compressed = gzipSync(Buffer.from(JSON.stringify(envelope), 'utf8'));
    const compressedHash = `sha256:${requireHash(compressed)}`;
    return {
        envelope,
        compressed: new Uint8Array(compressed),
        manifest: {
            snapshotVersion: version,
            parentVersion: parent,
            schemaVersion: 3,
            stateHash,
            compressedHash,
            encoding: 'gzip',
            compressedBytes: compressed.length,
            uncompressedBytes: Buffer.byteLength(JSON.stringify(envelope)),
            serverInstanceId: 'srv-test',
        },
    };
}

import { createHash } from 'node:crypto';
function requireHash(input) {
    return createHash('sha256').update(input).digest('hex');
}

const STATE_A = {
    tasks: { 'task-1': { title: '开会', note: '', completed: false, itemType: 'task', lifecycle: 'active', deletedAt: null } },
    customLists: {}, journals: {}, goals: {}, insights: {},
};

function nodeDecompress(compressed) {
    return new Uint8Array(gunzipSync(Buffer.from(compressed)));
}

function stubFetch(snapshot, { corruptPayload = false, failStatus = null } = {}) {
    const calls = [];
    const fetchFn = async (url, opts = {}) => {
        calls.push({ url, opts });
        if (url.endsWith('/snapshots/latest')) {
            return { status: 200, ok: true, json: async () => snapshot.manifest };
        }
        if (url.includes('/snapshots/')) {
            if (failStatus) return { status: failStatus, ok: false, arrayBuffer: async () => new ArrayBuffer(0) };
            const payload = corruptPayload
                ? snapshot.compressed.slice(0, snapshot.compressed.length - 8)
                : snapshot.compressed;
            return { status: 200, ok: true, arrayBuffer: async () => payload.buffer.slice(payload.byteOffset, payload.byteOffset + payload.byteLength) };
        }
        if (url.includes('/snapshot-acks')) {
            return { status: 202, ok: true };
        }
        throw new Error(`unexpected fetch: ${url}`);
    };
    return { calls, fetchFn };
}

describe('snapshot installer', () => {
    test('installs a browser-decoded direct latest snapshot using response headers', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ state: STATE_A });
        const decoded = new TextEncoder().encode(JSON.stringify(snap.envelope));
        const calls = [];
        const fetchFn = async (url) => {
            calls.push(url);
            return {
                status: 200,
                ok: true,
                headers: new Headers({
                    'content-type': 'application/octet-stream',
                    'content-encoding': 'gzip',
                    'x-snapshot-version': String(snap.manifest.snapshotVersion),
                    'x-state-hash': snap.manifest.stateHash,
                    etag: `W/"${snap.manifest.compressedHash}"`,
                }),
                arrayBuffer: async () => decoded.buffer,
            };
        };
        const installer = createSnapshotInstaller({
            store,
            fetchFn,
            serverUrl: '',
            decompress: nodeDecompress,
            ackInstalled: false,
        });

        await expect(installer.syncToLatest()).resolves.toMatchObject({ installed: true, version: 7 });
        expect(calls).toEqual(['/tplanner/v3/snapshots/latest']);
        expect(await installer.getServerMirror()).toEqual(STATE_A);
        const meta = await loadSyncMeta(store);
        expect(meta.installedSnapshotCompressedHash).toBe(snap.manifest.compressedHash);
    });

    test('uses the stored compressed hash for a conditional latest request', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ state: STATE_A });
        const decoded = new TextEncoder().encode(JSON.stringify(snap.envelope));
        const requests = [];
        const fetchFn = async (_url, options) => {
            requests.push(options);
            if (requests.length === 2) return { status: 304, ok: false, headers: new Headers() };
            return {
                status: 200,
                ok: true,
                headers: new Headers({
                    'content-type': 'application/octet-stream',
                    'x-snapshot-version': '7',
                    'x-state-hash': snap.manifest.stateHash,
                    etag: `"${snap.manifest.compressedHash}"`,
                }),
                arrayBuffer: async () => decoded.buffer,
            };
        };
        const installer = createSnapshotInstaller({ store, fetchFn, serverUrl: '', ackInstalled: false });

        await installer.syncToLatest();
        await expect(installer.syncToLatest()).resolves.toMatchObject({ installed: false, skipped: true, version: 7 });
        expect(requests[1].headers['if-none-match']).toBe(`"${snap.manifest.compressedHash}"`);
    });

    test('rejects an HTML SPA fallback before parsing or changing the mirror', async () => {
        const store = createMemoryKvStore();
        const installer = createSnapshotInstaller({
            store,
            serverUrl: '',
            fetchFn: async () => ({
                status: 200,
                ok: true,
                headers: new Headers({ 'content-type': 'text/html; charset=utf-8' }),
            }),
        });

        await expect(installer.syncToLatest()).rejects.toThrow(/returned text\/html.*SPA fallback/i);
        await expect(installer.getServerMirror()).resolves.toBeUndefined();
    });

    test('rejects an HTML 404 instead of treating it as an empty snapshot store', async () => {
        const store = createMemoryKvStore();
        const installer = createSnapshotInstaller({
            store,
            serverUrl: '',
            fetchFn: async () => ({
                status: 404,
                ok: false,
                headers: new Headers({ 'content-type': 'text/html; charset=utf-8' }),
            }),
        });

        await expect(installer.syncToLatest()).rejects.toThrow(/text\/html.*SPA fallback/i);
        await expect(installer.getServerMirror()).resolves.toBeUndefined();
    });

    test('downloads, verifies and atomically installs a snapshot', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ state: STATE_A });
        const { fetchFn, calls } = stubFetch(snap);
        const installer = createSnapshotInstaller({ store, fetchFn, serverUrl: 'https://sync.example', decompress: nodeDecompress });

        const result = await installer.syncToLatest();
        expect(result.installed).toBe(true);
        expect(result.version).toBe(7);

        expect(await installer.getServerMirror()).toEqual(STATE_A);
        const meta = await loadSyncMeta(store);
        expect(meta.installedSnapshotVersion).toBe(7);
        expect(meta.installedSnapshotHash).toBe(snap.manifest.stateHash);
        expect(calls.some((c) => c.url.includes('/snapshot-acks'))).toBe(true);
        const download = calls.find((c) => /\/snapshots\/7$/.test(c.url));
        expect(download.opts.headers?.['if-none-match']).toBeUndefined();
    });

    test('skips when already installed', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ state: STATE_A });
        const { fetchFn } = stubFetch(snap);
        const installer = createSnapshotInstaller({ store, fetchFn, serverUrl: 'https://sync.example', decompress: nodeDecompress });

        await installer.syncToLatest();
        const again = await installer.syncToLatest();
        expect(again.skipped).toBe(true);
    });

    test('corrupted payload fails with ERROR006 and leaves old mirror untouched', async () => {
        const store = createMemoryKvStore();
        const first = buildSnapshot({ version: 6, state: { ...STATE_A, tasks: { 'task-1': { ...STATE_A.tasks['task-1'], title: '旧' } } } });
        const second = buildSnapshot({ version: 7, state: STATE_A });

        const installer1 = createSnapshotInstaller({ store, fetchFn: stubFetch(first).fetchFn, serverUrl: 'https://sync.example', decompress: nodeDecompress });
        await installer1.syncToLatest();
        const mirrorBefore = await installer1.getServerMirror();

        const { fetchFn } = stubFetch(second, { corruptPayload: true });
        const installer2 = createSnapshotInstaller({ store, fetchFn, serverUrl: 'https://sync.example', decompress: nodeDecompress });
        await expect(installer2.syncToLatest()).rejects.toMatchObject({ code: 'ERROR006' });

        expect(await installer2.getServerMirror()).toEqual(mirrorBefore, 'failed install must not touch the old mirror');
        const meta = await loadSyncMeta(store);
        expect(meta.installedSnapshotVersion).toBe(6);
    });

    test('pending commands are replayed over the new mirror (display overlay)', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ state: STATE_A });
        await appendCommands(store, [{ type: 'task.setTitle', aggregateId: 'task-1', arguments: { title: '本地未确认' } }]);

        const { fetchFn } = stubFetch(snap);
        const installer = createSnapshotInstaller({ store, fetchFn, serverUrl: 'https://sync.example', decompress: nodeDecompress });
        await installer.syncToLatest();

        const display = await installer.getDisplayState();
        expect(display.tasks['task-1'].title).toBe('本地未确认', 'pending overlay survives snapshot install');
        expect(await installer.getServerMirror()).toEqual(STATE_A, 'mirror stays authoritative');
    });

    test('uploaded commands remain in the display overlay until a terminal receipt arrives', async () => {
        const store = createMemoryKvStore();
        const snap = buildSnapshot({ state: STATE_A });
        const [command] = await appendCommands(store, [
            { type: 'task.setTitle', aggregateId: 'task-1', arguments: { title: '已上传未确认' } },
        ]);
        await markUploaded(store, [command.clientSequence]);

        const { fetchFn } = stubFetch(snap);
        const installer = createSnapshotInstaller({
            store,
            fetchFn,
            serverUrl: 'https://sync.example',
            decompress: nodeDecompress,
            ackInstalled: false,
        });
        await installer.syncToLatest();

        expect((await installer.getDisplayState()).tasks['task-1'].title).toBe('已上传未确认');
        expect(await installer.getServerMirror()).toEqual(STATE_A);
    });

    test('one atomic mutation removes commands covered by either snapshot or broker proof and rebuilds display', async () => {
        const store = createMemoryKvStore();
        const commands = await appendCommands(store, [
            { type: 'task.setTitle', aggregateId: 'task-1', arguments: { title: 'snapshot-covered' } },
            { type: 'task.setNote', aggregateId: 'task-1', arguments: { note: 'broker-covered' } },
        ]);
        await markUploaded(store, commands.map((command) => command.clientSequence));
        await applyReceipts(store, [
            {
                commandId: commands[0].commandId,
                clientSequence: commands[0].clientSequence,
                brokerSequence: 99,
                status: 'APPLIED',
                snapshotVersion: 7,
            },
            {
                commandId: commands[1].commandId,
                clientSequence: commands[1].clientSequence,
                brokerSequence: 2,
                status: 'NOOP',
                snapshotVersion: 99,
            },
        ]);
        const mutateMany = vi.spyOn(store, 'mutateMany');
        const snap = buildSnapshot({ version: 7, brokerToSequence: 2, state: STATE_A });
        const installer = createSnapshotInstaller({
            store,
            fetchFn: stubFetch(snap).fetchFn,
            serverUrl: 'https://sync.example',
            decompress: nodeDecompress,
            ackInstalled: false,
        });

        await installer.syncToLatest();

        expect(await listCommands(store, { state: ['pending', 'uploaded'] })).toEqual([]);
        expect(await installer.getDisplayState()).toEqual(STATE_A);
        expect(mutateMany).toHaveBeenCalledTimes(1);
        expect(mutateMany).toHaveBeenCalledWith(expect.objectContaining({
            deleteKeys: ['cmd:1', 'cmd:2'],
            setEntries: expect.arrayContaining([['display', STATE_A]]),
        }));
        expect((await loadSyncMeta(store)).installedBrokerToSequence).toBe(2);
    });

    test('a terminal receipt arriving after the same snapshot rebases and cleans its overlay', async () => {
        const store = createMemoryKvStore();
        const [command] = await appendCommands(store, [
            { type: 'task.setTitle', aggregateId: 'task-1', arguments: { title: 'optimistic' } },
        ]);
        await markUploaded(store, [command.clientSequence]);
        const snap = buildSnapshot({ version: 7, brokerToSequence: 2, state: STATE_A });
        const installer = createSnapshotInstaller({
            store,
            fetchFn: stubFetch(snap).fetchFn,
            serverUrl: 'https://sync.example',
            decompress: nodeDecompress,
            ackInstalled: false,
        });
        await installer.syncToLatest();
        expect((await installer.getDisplayState()).tasks['task-1'].title).toBe('optimistic');
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(1);

        await applyReceipts(store, [{
            commandId: command.commandId,
            clientSequence: command.clientSequence,
            brokerSequence: 2,
            status: 'APPLIED',
            snapshotVersion: 7,
        }]);
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(1);
        const mutateMany = vi.spyOn(store, 'mutateMany');

        await installer.rebaseDisplay();

        expect(await listCommands(store, { state: 'uploaded' })).toEqual([]);
        expect(await installer.getDisplayState()).toEqual(STATE_A);
        expect(mutateMany).toHaveBeenCalledWith({
            deleteKeys: ['cmd:1'],
            setEntries: [['display', STATE_A]],
        });
    });

    test('uncovered receipts and receipts without either proof never delete their overlays', async () => {
        const store = createMemoryKvStore();
        const commands = await appendCommands(store, [
            { type: 'task.setTitle', aggregateId: 'task-1', arguments: { title: 'future proof' } },
            { type: 'task.setNote', aggregateId: 'task-1', arguments: { note: 'no proof' } },
        ]);
        await markUploaded(store, commands.map((command) => command.clientSequence));
        await applyReceipts(store, [
            {
                commandId: commands[0].commandId,
                clientSequence: commands[0].clientSequence,
                brokerSequence: 3,
                status: 'APPLIED',
                snapshotVersion: 8,
            },
            {
                commandId: commands[1].commandId,
                clientSequence: commands[1].clientSequence,
                status: 'NOOP',
            },
        ]);
        const snap = buildSnapshot({ version: 7, brokerToSequence: 2, state: STATE_A });
        const installer = createSnapshotInstaller({
            store,
            fetchFn: stubFetch(snap).fetchFn,
            serverUrl: 'https://sync.example',
            decompress: nodeDecompress,
            ackInstalled: false,
        });

        await installer.syncToLatest();
        await installer.rebaseDisplay();

        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(2);
        expect((await installer.getDisplayState()).tasks['task-1']).toMatchObject({
            title: 'future proof',
            note: 'no proof',
        });
    });

    test('a failed atomic mutation leaves mirror, display, metadata, and covered command untouched', async () => {
        const store = createMemoryKvStore();
        const [command] = await appendCommands(store, [
            { type: 'task.setTitle', aggregateId: 'task-1', arguments: { title: 'optimistic' } },
        ]);
        await markUploaded(store, [command.clientSequence]);
        await applyReceipts(store, [{
            commandId: command.commandId,
            clientSequence: command.clientSequence,
            brokerSequence: 2,
            status: 'APPLIED',
            snapshotVersion: 7,
        }]);
        store.mutateMany = vi.fn(async () => { throw new Error('simulated transaction abort'); });
        const snap = buildSnapshot({ version: 7, brokerToSequence: 2, state: STATE_A });
        const installer = createSnapshotInstaller({
            store,
            fetchFn: stubFetch(snap).fetchFn,
            serverUrl: 'https://sync.example',
            decompress: nodeDecompress,
            ackInstalled: false,
        });

        await expect(installer.syncToLatest()).rejects.toThrow('simulated transaction abort');

        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(1);
        await expect(installer.getServerMirror()).resolves.toBeUndefined();
        await expect(installer.getDisplayState()).resolves.toBeUndefined();
        expect((await loadSyncMeta(store)).installedSnapshotVersion).toBe(0);
    });

    test('rejects an unexpected 304 when no local snapshot validator was sent', async () => {
        const store = createMemoryKvStore();
        const installer = createSnapshotInstaller({
            store,
            serverUrl: '',
            fetchFn: async () => ({ status: 304, ok: false, headers: new Headers() }),
            ackInstalled: false,
        });

        await expect(installer.syncToLatest()).rejects.toMatchObject({ code: 'ERROR008' });
        await expect(installer.getServerMirror()).resolves.toBeUndefined();
        expect((await loadSyncMeta(store)).installedSnapshotVersion).toBe(0);
    });

    test('rejects a malformed V3 envelope before changing mirror or metadata', async () => {
        const store = createMemoryKvStore();
        const malformedState = {
            tasks: STATE_A.tasks,
            customLists: {},
            journals: {},
            goals: {},
            // insights is deliberately absent.
        };
        const snap = buildSnapshot({ state: malformedState });
        const installer = createSnapshotInstaller({
            store,
            fetchFn: stubFetch(snap).fetchFn,
            serverUrl: 'https://sync.example',
            decompress: nodeDecompress,
            ackInstalled: false,
        });

        await expect(installer.syncToLatest()).rejects.toMatchObject({ code: 'ERROR006' });
        await expect(installer.getServerMirror()).resolves.toBeUndefined();
        expect((await loadSyncMeta(store)).installedSnapshotVersion).toBe(0);
    });
});
