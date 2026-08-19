import { describe, expect, test } from 'vitest';
import { createSyncAdapter } from './syncLogic';

const adapter = createSyncAdapter({
    type: 'records',
    endpoint: '/records',
    toEntity: item => ({
        id: item.id,
        payload: item,
        updatedAt: item.updatedAt,
        deletedAt: item.deletedAt ?? null,
    }),
    fromEntity: entity => entity.payload,
});

describe('SyncAdapter conflict result', () => {
    test('returns display-ready manual conflicts for the desktop warning', () => {
        const base = { a: 'previous-content-key' };
        const local = [{ id: 'a', text: 'local', updatedAt: 2 }];
        const remote = [{ id: 'a', text: 'remote', updatedAt: 3 }];

        const result = adapter.mergeWithBase(local, remote, base);

        expect(result.unresolved).toBe(1);
        expect(result.analysis.manual).toEqual([{
            id: 'a',
            local: local[0],
            remote: remote[0],
        }]);
    });
});
