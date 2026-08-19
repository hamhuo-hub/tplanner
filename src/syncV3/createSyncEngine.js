// Electron 与 Web 共用的 V3 同步引擎装配(见 docs/sync-v3.md §16/§17):
// 同一个 IndexedDB 镜像 + 命令 outbox + 协议层,两端只差 UI 外壳。
// Web 端不再把树莓派 REST 数据当直接状态,而是与本引擎共用本地镜像。
import { createIndexedDbKvStore } from './kvStore';
import { createUploader } from './uploader';
import { createSnapshotInstaller } from './snapshotInstaller';
import { createNotificationClient } from './notificationClient';

export async function createSyncEngine({
    serverUrl,
    fetchFn = (...args) => fetch(...args),
    store = createIndexedDbKvStore(),
    waitMs = 25_000,
    decompress,
} = {}) {
    const uploader = createUploader({ store, fetchFn, serverUrl });
    const installer = createSnapshotInstaller({ store, fetchFn, serverUrl, decompress });
    const notifications = createNotificationClient({
        store,
        fetchFn,
        serverUrl,
        waitMs,
        onNewVersion: () => installer.syncToLatest(),
    });

    return {
        store,
        uploader,
        installer,
        notifications,
        /** 手动同步(§16):排空上传 → 收受回执 → 拉最新快照并安装。 */
        async syncNow() {
            await uploader.flush();
            const installed = await installer.syncToLatest();
            return {
                pending: (await import('./commandOutbox')).listCommands(store, { state: 'pending' }),
                installed,
            };
        },
    };
}
