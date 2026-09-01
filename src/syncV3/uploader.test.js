import { describe, test, expect } from 'vitest';
import { createMemoryKvStore } from './kvStore';
import { appendCommands, getReceipt, listCommands, markUploaded } from './commandOutbox';
import { createUploader } from './uploader';

function jsonResponse(status, body) {
    return { status, ok: status >= 200 && status < 300, json: async () => body };
}

describe('uploader', () => {
    test('empty server URL uses same-origin routes', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);

        const calls = [];
        const uploader = createUploader({
            store,
            serverUrl: '',
            fetchFn: async (url, opts = {}) => {
                calls.push(url);
                if (url.includes('command-batches')) {
                    const batch = JSON.parse(opts.body);
                    return jsonResponse(202, { batchId: batch.batchId, state: 'BROKER_PERSISTED' });
                }
                return jsonResponse(200, { acceptedThrough: 0, results: [] });
            },
        });

        await expect(uploader.flush()).resolves.toEqual({ uploaded: 1 });
        expect(calls[0]).toBe('/tplanner/v3/command-batches');
        expect(calls[1]).toMatch(/^\/tplanner\/v3\/receipts\?deviceId=.+&afterClientSequence=0$/);
    });

    test('missing server URL is rejected', async () => {
        const uploader = createUploader({
            store: createMemoryKvStore(),
            fetchFn: async () => jsonResponse(202, {}),
        });

        await expect(uploader.pump()).rejects.toThrow('sync server url not configured');
    });

    test('HTML acknowledgement is rejected without marking commands uploaded', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);
        const uploader = createUploader({
            store,
            serverUrl: '',
            fetchFn: async () => ({
                status: 202,
                ok: true,
                headers: new Headers({ 'content-type': 'text/html; charset=utf-8' }),
                json: async () => { throw new SyntaxError('Unexpected token <'); },
            }),
        });

        await expect(uploader.pump()).rejects.toThrow(/text\/html.*SPA fallback/i);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(1);
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(0);
    });

    test('malformed acknowledgement is rejected without marking commands uploaded', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);
        const uploader = createUploader({
            store,
            serverUrl: '',
            fetchFn: async () => jsonResponse(202, {}),
        });

        await expect(uploader.pump()).rejects.toThrow(/invalid acknowledgement/i);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(1);
    });

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
                return jsonResponse(202, {
                    batchId: calls.at(-1).body.batchId,
                    brokerSequence: 42,
                    state: 'BROKER_PERSISTED',
                });
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

    test('collectReceipts persists terminal receipts without deleting uploaded commands', async () => {
        const store = createMemoryKvStore();
        const [c1] = await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);
        const [c2] = await appendCommands(store, [{ type: 'task.setTitle', aggregateId: 't1' }]);
        await markUploaded(store, [c1.clientSequence, c2.clientSequence]);

        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async () => jsonResponse(200, {
                acceptedThrough: 2,
                results: [
                    {
                        commandId: c1.commandId,
                        clientSequence: 1,
                        brokerSequence: 101,
                        status: 'APPLIED',
                        snapshotVersion: 9,
                    },
                    {
                        commandId: c2.commandId,
                        clientSequence: 2,
                        brokerSequence: 102,
                        status: 'APPLIED',
                        snapshotVersion: 9,
                    },
                ],
            }),
        });

        const { acceptedThrough } = await uploader.collectReceipts();
        expect(acceptedThrough).toBe(2);
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(2);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(0);
        expect(await getReceipt(store, 2)).toMatchObject({
            commandId: c2.commandId,
            brokerSequence: 102,
            snapshotVersion: 9,
        });
    });

    test('collectReceipts follows pagination beyond the 200-result server page size', async () => {
        const store = createMemoryKvStore();
        const commands = await appendCommands(store, Array.from({ length: 201 }, (_, i) => ({
            type: 'task.create',
            aggregateId: `t${i}`,
        })));
        await markUploaded(store, commands.map((command) => command.clientSequence));
        const receipts = commands.map((command) => ({
            commandId: command.commandId,
            clientSequence: command.clientSequence,
            brokerSequence: command.clientSequence,
            status: 'APPLIED',
            snapshotVersion: 9,
        }));
        const requestedAfter = [];
        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async (url) => {
                const after = Number(new URL(url).searchParams.get('afterClientSequence'));
                requestedAfter.push(after);
                return jsonResponse(200, {
                    acceptedThrough: receipts.length,
                    results: receipts.filter((receipt) => receipt.clientSequence > after).slice(0, 200),
                });
            },
        });

        await expect(uploader.collectReceipts()).resolves.toEqual({ acceptedThrough: 201, applied: 201 });
        expect(requestedAfter).toEqual([0, 200]);
        expect(await listCommands(store, { state: 'uploaded', limit: 500 })).toHaveLength(201);
    });

    test('collectReceipts rejects receipt statuses outside the protocol enum', async () => {
        const store = createMemoryKvStore();
        const [command] = await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);
        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async () => jsonResponse(200, {
                acceptedThrough: 1,
                results: [{
                    commandId: command.commandId,
                    clientSequence: command.clientSequence,
                    brokerSequence: 1,
                    status: 'UNKNOWN',
                }],
            }),
        });

        await expect(uploader.collectReceipts()).rejects.toThrow(/invalid receipt/i);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(1);
    });

    test('flush drains multiple batches until empty', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, Array.from({ length: 3 }, (_, i) => ({ type: 'task.create', aggregateId: `t${i}` })));

        const uploader = createUploader({
            store,
            serverUrl: 'https://sync.example',
            fetchFn: async (url, opts = {}) => {
                if (url.includes('command-batches')) {
                    const batch = JSON.parse(opts.body);
                    return jsonResponse(202, { batchId: batch.batchId, state: 'BROKER_PERSISTED' });
                }
                return jsonResponse(200, { acceptedThrough: 0, results: [] });
            },
        });

        const { uploaded } = await uploader.flush();
        expect(uploaded).toBe(3);
        expect(await listCommands(store, { state: 'pending' })).toHaveLength(0);
    });
});
