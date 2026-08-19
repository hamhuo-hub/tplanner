import { describe, test, expect } from 'vitest';
import { createMemoryKvStore } from './kvStore';
import { appendCommands, listCommands } from './commandOutbox';
import { createUploader } from './uploader';

function jsonResponse(status, body) {
    return { status, ok: status >= 200 && status < 300, json: async () => body };
}

describe('uploader', () => {
    test('pump posts one batch with Idempotency-Key and marks uploaded', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, [
            { type: 'task.create', aggregateId: 't1', arguments: { title: 'a' } },
            { type: 'task.setTitle', aggregateId: 't1', arguments: { title: 'b' } },
        ]);

        const calls = [];
        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async (url, opts) => {
                calls.push({ url, opts, body: JSON.parse(opts.body) });
                return jsonResponse(202, { batchId: 'b1', brokerSequence: 42, state: 'BROKER_PERSISTED' });
            },
        });

        const { uploaded } = await uploader.pump();
        expect(uploaded).toBe(2);
        expect(calls).toHaveLength(1);
        expect(calls[0].url).toBe('https://sync.example/tplanner/v3/command-batches');
        expect(calls[0].opts.headers['idempotency-key']).toBe(calls[0].body.batchId);
        expect(calls[0].body.protocolVersion).toBe(3);
        expect(calls[0].body.firstClientSequence).toBe(1);
        expect(calls[0].body.lastClientSequence).toBe(2);
        expect(calls[0].body.commands).toHaveLength(2);

        expect(await listCommands(store, { state: 'pending' })).toHaveLength(0);
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(2);
    });

    test('broker rejection leaves outbox intact and throws with status', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);

        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async () => jsonResponse(503, { error: 'BROKER_UNAVAILABLE' }),
        });

        await expect(uploader.pump()).rejects.toThrow(/503/);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(1, 'pending command survives a failed upload');
    });

    test('collectReceipts removes confirmed commands and persists receipts', async () => {
        const store = createMemoryKvStore();
        const [c1] = await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);
        await appendCommands(store, [{ type: 'task.setTitle', aggregateId: 't1' }]);

        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async () => jsonResponse(200, {
                acceptedThrough: 2,
                results: [
                    { commandId: c1.commandId, clientSequence: 1, status: 'APPLIED', snapshotVersion: 9 },
                    { commandId: 'c2', clientSequence: 2, status: 'APPLIED', snapshotVersion: 9 },
                ],
            }),
        });

        const { acceptedThrough } = await uploader.collectReceipts();
        expect(acceptedThrough).toBe(2);
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(0);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(0);
    });

    test('flush drains multiple batches until empty', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, Array.from({ length: 3 }, (_, i) => ({ type: 'task.create', aggregateId: `t${i}` })));

        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async (url) =>
                url.includes('command-batches')
                    ? jsonResponse(202, { state: 'BROKER_PERSISTED' })
                    : jsonResponse(200, { acceptedThrough: 0, results: [] }),
        });

        const { uploaded } = await uploader.flush();
        expect(uploaded).toBe(3);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(0);
    });
});
