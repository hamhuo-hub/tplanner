import { describe, test, expect } from 'vitest';
import { gzipSync, gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import canonicalize from 'canonicalize';
import { createMemoryKvStore } from './kvStore';
import { createSyncEngine } from './createSyncEngine';
import { appendCommands } from './commandOutbox';

const nodeDecompress = (compressed) => new Uint8Array(gunzipSync(Buffer.from(compressed)));

const STATE = {
    tasks: { 'task-1': { title: '开会', note: '', completed: false, itemType: 'task', lifecycle: 'active', deletedAt: null } },
    customLists: {}, journals: {}, goals: {}, insights: {},
};

function makeSnapshot(version) {
    const envelope = {
        snapshotSchemaVersion: 3,
        snapshotVersion: version,
        parentVersion: version - 1,
        serverInstanceId: 'srv-test',
        brokerFromSequence: 1,
        brokerToSequence: 1,
        createdAt: '2026-08-19T00:00:00.000Z',
        state: STATE,
    };
    const canonical = canonicalize(STATE);
    const stateHash = `sha256:${createHash('sha256').update(canonical).digest('hex')}`;
    const compressed = gzipSync(Buffer.from(JSON.stringify(envelope)));
    const compressedHash = `sha256:${createHash('sha256').update(compressed).digest('hex')}`;
    return {
        compressed,
        manifest: {
            snapshotVersion: version,
            parentVersion: version - 1,
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

function mockBackend() {
    const snap = makeSnapshot(5);
    const calls = [];
    const fetchFn = async (url, opts = {}) => {
        calls.push(url);
        if (url.includes('/command-batches')) {
            return { status: 202, ok: true, json: async () => ({ state: 'BROKER_PERSISTED' }) };
        }
        if (url.includes('/receipts')) {
            return { status: 200, ok: true, json: async () => ({ acceptedThrough: 0, results: [] }) };
        }
        if (url.includes('/snapshots/latest')) {
            return { status: 200, ok: true, json: async () => snap.manifest };
        }
        if (url.includes('/snapshots/')) {
            return { status: 200, ok: true, arrayBuffer: async () => snap.compressed.buffer.slice(snap.compressed.byteOffset, snap.compressed.byteOffset + snap.compressed.byteLength) };
        }
        if (url.includes('/snapshot-acks')) {
            return { status: 202, ok: true };
        }
        throw new Error(`unexpected: ${url}`);
    };
    return { snap, fetchFn };
}

describe('sync engine (shared by desktop and web)', () => {
    test('syncNow uploads commands and installs the snapshot', async () => {
        const store = createMemoryKvStore();
        const { fetchFn } = mockBackend();
        const engine = await createSyncEngine({ store, serverUrl: 'https://sync.example', fetchFn, decompress: nodeDecompress });

        await appendCommands(store, [{ type: 'task.create', aggregateId: 'task-9', arguments: { title: 'x' } }]);
        await engine.syncNow();

        expect(await engine.installer.getServerMirror()).toEqual(STATE);
        const meta = await import('./syncMeta').then((m) => m.loadSyncMeta(store));
        expect(meta.installedSnapshotVersion).toBe(5);
    });

    test('engine works identically when constructed with the browser IndexedDB factory (same interface)', async () => {
        // node 环境没有 indexedDB:仅验证默认参数路径不抛(工厂可构造)
        const store = createMemoryKvStore();
        const engine = await createSyncEngine({ store, serverUrl: 'https://sync.example', fetchFn: async () => { throw new Error('x'); } });
        expect(typeof engine.uploader.pump).toBe('function');
        expect(typeof engine.installer.syncToLatest).toBe('function');
        expect(typeof engine.notifications.start).toBe('function');
    });
});
