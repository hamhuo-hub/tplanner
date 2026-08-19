import { describe, test, expect } from 'vitest';
import { createMemoryKvStore } from './kvStore';
import { updateSyncMeta } from './syncMeta';
import { createNotificationClient } from './notificationClient';

function jsonResponse(status, body) {
    return { status, ok: status >= 200 && status < 300, json: async () => body };
}

describe('notification client', () => {
    test('notifies only when latestVersion is above installed', async () => {
        const store = createMemoryKvStore();
        await updateSyncMeta(store, { installedSnapshotVersion: 7 });

        const client = createNotificationClient({
            store,
            serverUrl: 'https://sync.example',
            waitMs: 10,
            fetchFn: async (url) => {
                expect(url).toContain('afterVersion=7');
                expect(url).toContain('wait=10');
                return jsonResponse(200, { latestVersion: 7, stateHash: 'sha256:x' });
            },
        });

        const { notified } = await client.pollOnce();
        expect(notified).toBe(false);
    });

    test('fires onNewVersion when a newer snapshot exists', async () => {
        const store = createMemoryKvStore();
        await updateSyncMeta(store, { installedSnapshotVersion: 7 });

        const seen = [];
        const client = createNotificationClient({
            store,
            serverUrl: 'https://sync.example',
            waitMs: 10,
            fetchFn: async () => jsonResponse(200, { latestVersion: 8, stateHash: 'sha256:abc', ownAcceptedThrough: 42 }),
            onNewVersion: (body) => seen.push(body),
        });

        const { notified } = await client.pollOnce();
        expect(notified).toBe(true);
        expect(seen).toHaveLength(0, 'pollOnce 只报告,不触发回调');
    });

    test('pollAndSync installs after a notification', async () => {
        const store = createMemoryKvStore();
        await updateSyncMeta(store, { installedSnapshotVersion: 7 });

        let synced = 0;
        const client = createNotificationClient({
            store,
            serverUrl: 'https://sync.example',
            waitMs: 10,
            fetchFn: async () => jsonResponse(200, { latestVersion: 8, stateHash: 'sha256:abc' }),
        });

        const { notified } = await client.pollAndSync(async () => { synced += 1; });
        expect(notified).toBe(true);
        expect(synced).toBe(1);
    });

    test('network errors surface from pollOnce but not from the start loop', async () => {
        const store = createMemoryKvStore();
        const client = createNotificationClient({
            store,
            serverUrl: 'https://sync.example',
            waitMs: 5,
            fetchFn: async () => {
                throw new Error('network down');
            },
        });

        await expect(client.pollOnce()).rejects.toThrow(/network down/);

        // tickOnce(循环体)吞掉错误返回,start 循环因此不会向外抛
        const tick = await client.tickOnce();
        expect(tick.notified).toBe(false);
        expect(tick.error).toBeInstanceOf(Error);
    });
});
