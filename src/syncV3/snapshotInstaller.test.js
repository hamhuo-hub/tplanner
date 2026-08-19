import { describe, test, expect } from 'vitest';
import { gzipSync, gunzipSync } from 'node:zlib';
import canonicalize from 'canonicalize';
import { createMemoryKvStore } from './kvStore';
import { appendCommands } from './commandOutbox';
import { loadSyncMeta } from './syncMeta';
import { createSnapshotInstaller, sha256Hex } from './snapshotInstaller';

function buildSnapshot({ version = 7, parent = 6, state }) {
    const envelope = {
        snapshotSchemaVersion: 3,
        snapshotVersion: version,
        parentVersion: parent,
        serverInstanceId: 'srv-test',
        brokerFromSequence: 1,
        brokerToSequence: 2,
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
});
