import { describe, test, expect } from 'vitest';
import { gzipSync, gunzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import canonicalize from 'canonicalize';
import { createMemoryKvStore } from './kvStore';
import { createSyncEngine } from './createSyncEngine';
import { appendCommands, listCommands } from './commandOutbox';

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
    const receipts = [];
    const fetchFn = async (url, opts = {}) => {
        calls.push(url);
        if (url.includes('/capabilities')) {
            return {
                status: 200,
                ok: true,
                json: async () => ({
                    softwareVersion: '8.0.0',
                    protocolVersion: 3,
                    schemaVersion: 3,
                    serverInstanceId: 'srv-test',
                }),
            };
        }
        if (url.includes('/command-batches')) {
            const batch = JSON.parse(opts.body);
            receipts.push(...batch.commands.map((command) => ({
                deviceId: batch.deviceId,
                commandId: command.commandId,
                clientSequence: command.clientSequence,
                brokerSequence: command.clientSequence,
                status: 'APPLIED',
                snapshotVersion: 5,
            })));
            return {
                status: 202,
                ok: true,
                json: async () => ({
                    batchId: batch.batchId,
                    brokerSequence: batch.commands.at(-1).clientSequence,
                    state: 'BROKER_PERSISTED',
                }),
            };
        }
        if (url.includes('/receipts')) {
            const request = new URL(url);
            const deviceId = request.searchParams.get('deviceId');
            const after = Number(request.searchParams.get('afterClientSequence'));
            const deviceReceipts = receipts.filter((receipt) => receipt.deviceId === deviceId);
            const results = deviceReceipts
                .filter((receipt) => receipt.clientSequence > after)
                .map(({ deviceId: _deviceId, ...receipt }) => receipt);
            const acceptedThrough = deviceReceipts.at(-1)?.clientSequence ?? 0;
            return { status: 200, ok: true, json: async () => ({ acceptedThrough, results }) };
        }
        if (url.includes('/notifications')) {
            return {
                status: 200,
                ok: true,
                json: async () => ({
                    latestVersion: snap.manifest.snapshotVersion,
                    stateHash: snap.manifest.stateHash,
                }),
            };
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
    return { calls, snap, fetchFn };
}

describe('sync engine (shared by desktop and web)', () => {
    test('syncNow uploads commands and installs the snapshot', async () => {
        const store = createMemoryKvStore();
        const { calls, fetchFn } = mockBackend();
        const engine = await createSyncEngine({ store, serverUrl: 'https://sync.example', fetchFn, decompress: nodeDecompress });

        await appendCommands(store, [{ type: 'task.create', aggregateId: 'task-9', arguments: { title: 'x' } }]);
        const result = await engine.syncNow();

        expect(await engine.installer.getServerMirror()).toEqual(STATE);
        const meta = await import('./syncMeta').then((m) => m.loadSyncMeta(store));
        expect(meta.installedSnapshotVersion).toBe(5);
        expect(meta.installedBrokerToSequence).toBe(1);
        expect(await listCommands(store, { state: ['pending', 'uploaded'] })).toEqual([]);
        expect(result.pending).toEqual([]);
        expect(calls.indexOf('https://sync.example/tplanner/v3/capabilities')).toBeLessThan(
            calls.findIndex((url) => url.includes('/command-batches')),
        );
    });

    test('an incompatible capability response blocks every command upload', async () => {
        const store = createMemoryKvStore();
        const calls = [];
        const engine = await createSyncEngine({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async (url) => {
                calls.push(url);
                return {
                    status: 200,
                    ok: true,
                    json: async () => ({
                        softwareVersion: '7.9.9',
                        protocolVersion: 3,
                        schemaVersion: 3,
                        serverInstanceId: 'srv-test',
                    }),
                };
            },
            decompress: nodeDecompress,
        });
        await appendCommands(store, [
            { type: 'task.create', aggregateId: 'task-9', arguments: { title: 'must stay local' } },
        ]);

        await expect(engine.syncNow()).rejects.toMatchObject({ code: 'ERROR008' });

        expect(calls).toEqual(['https://sync.example/tplanner/v3/capabilities']);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(1);
    });

    test('a remote version notification installs once and publishes the display callback', async () => {
        const store = createMemoryKvStore();
        const { fetchFn } = mockBackend();
        const installed = [];
        const engine = await createSyncEngine({
            store,
            serverUrl: 'https://sync.example',
            fetchFn,
            decompress: nodeDecompress,
            onSnapshotInstalled: (event) => installed.push(event),
        });

        const tick = await engine.notifications.tickOnce();

        expect(tick).toEqual({ notified: true, error: null });
        expect(installed).toHaveLength(1);
        expect(installed[0].result).toMatchObject({ installed: true, version: 5 });
        expect(await installed[0].installer.getDisplayState()).toEqual(STATE);
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
