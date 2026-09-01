import { describe, test, expect } from 'vitest';
import { createMemoryKvStore } from './kvStore';
import { loadSyncMeta } from './syncMeta';
import { appendCommands, listCommands, markUploaded, applyReceipts, getReceipt } from './commandOutbox';

describe('command outbox', () => {
    test('appendCommands assigns contiguous clientSequences across calls', async () => {
        const store = createMemoryKvStore();
        const a = await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);
        const b = await appendCommands(store, [{ type: 'task.setTitle', aggregateId: 't1' }, { type: 'task.delete', aggregateId: 't1' }]);

        expect(a.map((c) => c.clientSequence)).toEqual([1]);
        expect(b.map((c) => c.clientSequence)).toEqual([2, 3]);
        expect(a[0].commandId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    });

    test('listCommands returns pending in sequence order', async () => {
        const store = createMemoryKvStore();
        await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }, { type: 'task.delete', aggregateId: 't1' }]);
        const pending = await listCommands(store);
        expect(pending.map((c) => c.clientSequence)).toEqual([1, 2]);
        expect(pending.every((c) => c.state === 'pending')).toBe(true);
    });

    test('uploaded commands stay in outbox until receipts confirm', async () => {
        const store = createMemoryKvStore();
        const commands = await appendCommands(store, [
            { type: 'task.create', aggregateId: 't1' },
            { type: 'task.setTitle', aggregateId: 't1' },
        ]);

        await markUploaded(store, [1]);
        expect(await listCommands(store)).toHaveLength(1, 'uploaded command is no longer pending');
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(1);

        // 回执确认后:删除条目、留存回执
        await applyReceipts(store, [
            { commandId: commands[0].commandId, clientSequence: 1, status: 'APPLIED', snapshotVersion: 7 },
            { commandId: commands[1].commandId, clientSequence: 2, status: 'APPLIED', snapshotVersion: 7 },
        ]);
        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(0);
        expect(await getReceipt(store, 2)).toMatchObject({ status: 'APPLIED', snapshotVersion: 7 });
    });

    test('SEQUENCE_GAP requeues the matching uploaded command', async () => {
        const store = createMemoryKvStore();
        const [command] = await appendCommands(store, [{ type: 'task.create', aggregateId: 't1' }]);
        await markUploaded(store, [command.clientSequence]);

        await applyReceipts(store, [{
            commandId: command.commandId,
            clientSequence: command.clientSequence,
            status: 'SEQUENCE_GAP',
            snapshotVersion: 7,
        }]);

        expect(await listCommands(store, { state: 'uploaded' })).toHaveLength(0);
        expect(await listCommands(store, { state: 'pending' })).toEqual([
            expect.objectContaining({ commandId: command.commandId, clientSequence: 1 }),
        ]);
        expect(await getReceipt(store, 1)).toMatchObject({ status: 'SEQUENCE_GAP' });
    });

    test('deviceId is stable across loads and sequences restart-safe via meta', async () => {
        const store = createMemoryKvStore();
        const first = await loadSyncMeta(store);
        const second = await loadSyncMeta(store);
        expect(second.deviceId).toBe(first.deviceId);
        expect(second.nextClientSequence).toBe(first.nextClientSequence);
    });
});
