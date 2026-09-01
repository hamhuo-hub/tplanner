import { afterEach, describe, expect, test, vi } from 'vitest';
import { createIndexedDbKvStore } from './kvStore';

function createFakeIndexedDb() {
    const rows = new Map();
    const objectStore = {
        get: vi.fn((key) => ({ result: rows.get(key) })),
        put: vi.fn(function put(row, key) {
            if (arguments.length < 2) {
                throw new DOMException('A key is required for this object store', 'DataError');
            }
            rows.set(key, structuredClone(row));
            return { result: key };
        }),
        delete: vi.fn((key) => {
            rows.delete(key);
            return { result: undefined };
        }),
        getAll: vi.fn(() => ({ result: [...rows.values()].map((row) => structuredClone(row)) })),
    };
    const db = {
        transaction: vi.fn(() => {
            const transaction = { objectStore: () => objectStore };
            queueMicrotask(() => transaction.oncomplete?.());
            return transaction;
        }),
    };
    const indexedDb = {
        open: vi.fn(() => {
            const request = { result: db };
            queueMicrotask(() => request.onsuccess?.());
            return request;
        }),
    };

    return { indexedDb, objectStore };
}

describe('indexedDbKvStore', () => {
    afterEach(() => vi.unstubAllGlobals());

    test('supplies the key when writing to an out-of-line-key object store', async () => {
        const { indexedDb, objectStore } = createFakeIndexedDb();
        vi.stubGlobal('indexedDB', indexedDb);
        const store = createIndexedDbKvStore({ dbName: 'test', storeName: 'kv' });

        await store.set('sync:meta', { version: 3 });

        expect(objectStore.put).toHaveBeenCalledWith(
            { key: 'sync:meta', value: { version: 3 } },
            'sync:meta',
        );
        await expect(store.get('sync:meta')).resolves.toEqual({ version: 3 });
        await expect(store.entries('sync:')).resolves.toEqual([
            ['sync:meta', { version: 3 }],
        ]);
    });
});
