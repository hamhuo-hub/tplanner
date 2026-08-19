import { describe, test, expect } from 'vitest';
import { createMemoryKvStore } from './kvStore';
import { createCommandRepository } from './commandRepository';
import { listCommands } from './commandOutbox';
import { emptyState } from './localReducer';

describe('command repository', () => {
    test('submit persists to outbox and optimistically updates display state', async () => {
        const store = createMemoryKvStore();
        let display = null;
        const repo = createCommandRepository({
            store,
            getDisplay: async () => display,
            setDisplay: async (next) => { display = next; },
        });

        const stamped = await repo.submit({ type: 'task.create', aggregateId: 't1', arguments: { title: '新任务' } });

        expect(stamped.clientSequence).toBe(1);
        expect(stamped.state).toBe('pending');
        expect(await listCommands(store)).toHaveLength(1);
        expect(display.tasks.t1.title).toBe('新任务');
        expect(display.tasks.t1.lifecycle).toBe('active');
    });

    test('two submits stack commands and keep the overlay consistent', async () => {
        const store = createMemoryKvStore();
        let display = emptyState();
        const repo = createCommandRepository({
            store,
            getDisplay: async () => display,
            setDisplay: async (next) => { display = next; },
        });

        await repo.submit({ type: 'task.create', aggregateId: 't1', arguments: { title: 'a' } });
        await repo.submit({ type: 'task.setCompleted', aggregateId: 't1', arguments: { completed: true } });

        expect(display.tasks.t1.completed).toBe(true);
        expect(await listCommands(store)).toHaveLength(2);
    });

    test('rejected command still lands in outbox but does not corrupt display', async () => {
        const store = createMemoryKvStore();
        let display = emptyState();
        const repo = createCommandRepository({
            store,
            getDisplay: async () => display,
            setDisplay: async (next) => { display = next; },
        });

        await repo.submit({ type: 'task.setTitle', aggregateId: 'ghost', arguments: { title: 'x' } });
        expect(display.tasks.ghost).toBeUndefined();
        expect(await listCommands(store)).toHaveLength(1);
    });
});
