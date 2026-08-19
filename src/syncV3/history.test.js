import { describe, test, expect } from 'vitest';
import { createMemoryKvStore } from './kvStore';
import { recordSnapshotInstall, listInstalls } from './history';

describe('history notices', () => {
    test('records installs newest-first with a cap', async () => {
        const store = createMemoryKvStore();
        for (let v = 1; v <= 60; v++) {
            await recordSnapshotInstall(store, { version: v, stateHash: `sha256:${v}`, at: v });
        }
        const list = await listInstalls(store);
        expect(list).toHaveLength(50, 'capped at 50 entries');
        expect(list[0].version).toBe(60);
        expect(list[49].version).toBe(11);
    });

    test('empty store yields an empty list', async () => {
        expect(await listInstalls(createMemoryKvStore())).toEqual([]);
    });
});
