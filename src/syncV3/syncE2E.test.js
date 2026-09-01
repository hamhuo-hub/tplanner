// 端到端收敛测试(见 docs/sync-v3.md §24):两台"设备"共享一个内存假服务器。
// 假服务器用与真服务器相同的 localReducer 语义顺序应用命令并生成快照,
// 证明:并发编辑按 broker 顺序裁决,两端安装同一版本后 canonical 状态完全一致。
import { describe, test, expect } from 'vitest';
import { gzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import canonicalize from 'canonicalize';
import { createMemoryKvStore } from './kvStore';
import { appendCommands } from './commandOutbox';
import { applyCommand, emptyState } from './localReducer';
import { createSyncEngine } from './createSyncEngine';

const nodeDecompress = async (c) => {
    const { gunzipSync: gunzip } = await import('node:zlib');
    return new Uint8Array(gunzip(Buffer.from(c)));
};

class FakeServer {
    constructor() {
        this.state = emptyState();
        this.seq = 0;
        this.version = 0;
        this.snapshots = new Map(); // version → { manifest, compressed }
        this.receipts = [];
    }

    receiveBatch(batch) {
        for (const command of batch.commands) {
            this.seq += 1;
            const result = applyCommand(this.state, command, this.seq);
            this.state = result.state;
            this.receipts.push({
                deviceId: batch.deviceId,
                commandId: command.commandId,
                clientSequence: command.clientSequence,
                brokerSequence: this.seq,
                status: result.receipt.status,
                errorCode: result.receipt.errorCode ?? null,
                snapshotVersion: this.version + 1,
            });
        }
        this.version += 1;
        const envelope = {
            snapshotSchemaVersion: 3,
            snapshotVersion: this.version,
            parentVersion: this.version - 1,
            serverInstanceId: 'srv-e2e',
            brokerFromSequence: this.seq - batch.commands.length + 1,
            brokerToSequence: this.seq,
            createdAt: new Date(this.version * 1000).toISOString(),
            state: this.state,
        };
        const canonical = canonicalize(this.state);
        const stateHash = `sha256:${createHash('sha256').update(canonical).digest('hex')}`;
        const compressed = gzipSync(Buffer.from(JSON.stringify(envelope)));
        const manifest = {
            snapshotVersion: this.version,
            parentVersion: this.version - 1,
            schemaVersion: 3,
            stateHash,
            compressedHash: `sha256:${createHash('sha256').update(compressed).digest('hex')}`,
            encoding: 'gzip',
            compressedBytes: compressed.length,
            uncompressedBytes: Buffer.byteLength(JSON.stringify(envelope)),
            serverInstanceId: 'srv-e2e',
        };
        this.snapshots.set(this.version, { manifest, compressed });
        return manifest;
    }

    async fetch(url, opts = {}) {
        if (url.includes('/capabilities')) {
            return {
                status: 200,
                ok: true,
                json: async () => ({
                    softwareVersion: '8.0.0',
                    protocolVersion: 3,
                    schemaVersion: 3,
                    serverInstanceId: 'srv-e2e',
                    latestSnapshotVersion: this.version,
                }),
            };
        }
        if (url.includes('/command-batches')) {
            const batch = JSON.parse(opts.body);
            this.receiveBatch(batch);
            return { status: 202, ok: true, json: async () => ({ batchId: batch.batchId, brokerSequence: this.seq, state: 'BROKER_PERSISTED', duplicate: false }) };
        }
        if (url.includes('/receipts')) {
            const request = new URL(url);
            const deviceId = request.searchParams.get('deviceId');
            const after = Number(request.searchParams.get('afterClientSequence'));
            const deviceReceipts = this.receipts.filter((receipt) => receipt.deviceId === deviceId);
            const results = deviceReceipts
                .filter((receipt) => receipt.clientSequence > after)
                .slice(0, 200)
                .map(({ deviceId: _deviceId, ...receipt }) => receipt);
            const acceptedThrough = deviceReceipts.at(-1)?.clientSequence ?? 0;
            return { status: 200, ok: true, json: async () => ({ acceptedThrough, results }) };
        }
        if (url.includes('/snapshots/latest')) {
            const { manifest } = this.snapshots.get(this.version);
            return { status: 200, ok: true, json: async () => manifest };
        }
        if (url.includes('/snapshot-acks')) {
            return { status: 202, ok: true };
        }
        const m = url.match(/\/snapshots\/(\d+)$/);
        if (m) {
            const snap = this.snapshots.get(Number(m[1]));
            if (!snap) return { status: 404, ok: false };
            return { status: 200, ok: true, arrayBuffer: async () => snap.compressed.buffer.slice(snap.compressed.byteOffset, snap.compressed.byteOffset + snap.compressed.byteLength) };
        }
        throw new Error(`unexpected url ${url}`);
    }
}

async function newDevice(server) {
    return createSyncEngine({
        store: createMemoryKvStore(),
        serverUrl: 'http://fake',
        fetchFn: (...args) => server.fetch(...args),
        decompress: nodeDecompress,
    });
}

describe('two-device convergence over a central reducer', () => {
    test('concurrent same-field edits settle on the later broker order and both devices converge', async () => {
        const server = new FakeServer();
        const deviceA = await newDevice(server);
        const deviceB = await newDevice(server);

        // A 创建任务,两端各拉一次 → 同一镜像 v1
        await appendCommands(deviceA.store, [
            { type: 'task.create', aggregateId: 't1', arguments: { title: '初始' } },
        ]);
        await deviceA.uploader.flush();
        await deviceA.installer.syncToLatest();
        await deviceB.installer.syncToLatest();
        expect((await deviceB.installer.getServerMirror()).tasks.t1.title).toBe('初始');

        // 两端并发改同一字段:A 先到 broker,B 后到
        await appendCommands(deviceA.store, [
            { type: 'task.setTitle', aggregateId: 't1', arguments: { title: 'A 的标题' } },
        ]);
        await appendCommands(deviceB.store, [
            { type: 'task.setTitle', aggregateId: 't1', arguments: { title: 'B 的标题' } },
        ]);
        await deviceA.uploader.flush();
        await deviceB.uploader.flush();

        // 双端安装最新快照
        await deviceA.installer.syncToLatest();
        await deviceB.installer.syncToLatest();

        const mirrorA = await deviceA.installer.getServerMirror();
        const mirrorB = await deviceB.installer.getServerMirror();
        expect(mirrorA.tasks.t1.title).toBe('B 的标题', 'later broker sequence wins');
        expect(mirrorB).toEqual(mirrorA, 'both devices hold byte-identical authoritative mirrors');
        expect(await deviceA.installer.getDisplayState()).toEqual(mirrorA, 'no pending overlay left after convergence');
    });

    test('different fields edited concurrently are both preserved', async () => {
        const server = new FakeServer();
        const deviceA = await newDevice(server);
        const deviceB = await newDevice(server);

        await appendCommands(deviceA.store, [
            { type: 'task.create', aggregateId: 't1', arguments: { title: 'x' } },
        ]);
        await deviceA.uploader.flush();
        await deviceB.installer.syncToLatest();

        await appendCommands(deviceA.store, [
            { type: 'task.setTitle', aggregateId: 't1', arguments: { title: '新标题' } },
        ]);
        await appendCommands(deviceB.store, [
            { type: 'task.setCompleted', aggregateId: 't1', arguments: { completed: true } },
        ]);
        await deviceA.uploader.flush();
        await deviceB.uploader.flush();
        await deviceA.installer.syncToLatest();
        await deviceB.installer.syncToLatest();

        const mirror = await deviceA.installer.getServerMirror();
        expect(mirror.tasks.t1.title).toBe('新标题');
        expect(mirror.tasks.t1.completed).toBe(true);
    });

    test('delete beats stale edits: editing after delete is rejected centrally', async () => {
        const server = new FakeServer();
        const deviceA = await newDevice(server);
        const deviceB = await newDevice(server);

        await appendCommands(deviceA.store, [
            { type: 'task.create', aggregateId: 't1', arguments: { title: 'x' } },
        ]);
        await deviceA.uploader.flush();
        await deviceB.installer.syncToLatest();

        // A 删除,B 离线期间改标题;B 的 stale 编辑后到 → ENTITY_DELETED
        await appendCommands(deviceA.store, [
            { type: 'task.delete', aggregateId: 't1', arguments: {} },
        ]);
        await deviceA.uploader.flush();
        await appendCommands(deviceB.store, [
            { type: 'task.setTitle', aggregateId: 't1', arguments: { title: '离线改' } },
        ]);
        await deviceB.uploader.flush();
        await deviceB.installer.syncToLatest();

        const mirror = await deviceB.installer.getServerMirror();
        expect(mirror.tasks.t1.lifecycle).toBe('deleted');
        expect(mirror.tasks.t1.title).toBe('x', 'stale edit must not mutate a deleted task');
    });
});
