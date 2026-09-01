// Electron 与 Web 共用的 V3 同步引擎装配(见 docs/sync-v3.md §16/§17):
// 同一个 IndexedDB 镜像 + 命令 outbox + 协议层,两端只差 UI 外壳。
// Web 端不再把树莓派 REST 数据当直接状态,而是与本引擎共用本地镜像。
import { createIndexedDbKvStore } from './kvStore';
import { createUploader } from './uploader';
import { createSnapshotInstaller } from './snapshotInstaller';
import { createDeltaInstaller } from './deltaInstaller';
import { createNotificationClient } from './notificationClient';
import { loadSyncMeta } from './syncMeta';
import { protocolError, readJsonResponse } from './httpResponse';

const EXPECTED_SOFTWARE_VERSION = '8.0.0';

async function verifyCapabilities({ store, fetchFn, serverUrl }) {
    const response = await fetchFn(`${serverUrl}/tplanner/v3/capabilities`, {
        headers: { 'cache-control': 'no-store' },
    });
    if (!response.ok) throw new Error(`capabilities request failed: ${response.status}`);
    const body = await readJsonResponse(response, 'capabilities request');
    if (body?.softwareVersion !== EXPECTED_SOFTWARE_VERSION
        || body?.protocolVersion !== 3
        || body?.schemaVersion !== 3
        || typeof body?.serverInstanceId !== 'string'
        || body.serverInstanceId === '') {
        throw protocolError('server capabilities do not match the TPlanner 8.0.0 V3 contract', response);
    }
    const meta = await loadSyncMeta(store);
    if (meta.serverInstanceId && meta.serverInstanceId !== body.serverInstanceId) {
        const error = new Error('server instance changed; re-bootstrap is required before upload');
        error.code = 'ERROR008';
        throw error;
    }
    return body;
}

export async function createSyncEngine({
    serverUrl,
    fetchFn = (...args) => fetch(...args),
    store = createIndexedDbKvStore(),
    waitMs = 25_000,
    decompress,
    onSnapshotInstalled,
    deltaEnabled = true,
} = {}) {
    const uploader = createUploader({ store, fetchFn, serverUrl });
    const installer = createSnapshotInstaller({ store, fetchFn, serverUrl, decompress });
    const deltaInstaller = createDeltaInstaller({ store, fetchFn, serverUrl, deltaEnabled });

    // 下行统一入口(§9.3):capability + 本地 canary 开关都允许且已有 cursor 时
    // 走 delta;任何断链/410/未知 type 都退回完整快照逃生舱。快照安装成功会
    // 用 manifest.cursor 重建 delta 起点,两条路径永远互不冲突。
    async function syncDownlink(capabilities) {
        const meta = await loadSyncMeta(store);
        if (deltaInstaller.shouldUseDelta(capabilities, meta)) {
            const result = await deltaInstaller.syncByCursor();
            if (result.mode === 'delta') return result;
            const installed = await installer.syncToLatest();
            return {
                mode: 'snapshot-fallback',
                fallbackReason: result.fallbackReason,
                ...installed,
            };
        }
        const installed = await installer.syncToLatest();
        return { mode: 'snapshot', ...installed };
    }

    const notifications = createNotificationClient({
        store,
        fetchFn,
        serverUrl,
        waitMs,
        onNewVersion: async () => {
            const capabilities = await verifyCapabilities({ store, fetchFn, serverUrl });
            const result = await syncDownlink(capabilities);
            if (result.installed) await onSnapshotInstalled?.({ result, installer });
            return result;
        },
    });

    return {
        store,
        uploader,
        installer,
        deltaInstaller,
        notifications,
        verifyCapabilities: () => verifyCapabilities({ store, fetchFn, serverUrl }),
        /** 手动同步(§16):排空上传 → 收受回执 → 下行(delta 优先,snapshot 逃生)。 */
        async syncNow() {
            // Stop a stale/mismatched client before the first command reaches another authority.
            const capabilities = await verifyCapabilities({ store, fetchFn, serverUrl });
            await uploader.flush();
            await installer.rebaseDisplay();
            const downlink = await syncDownlink(capabilities);
            return {
                ...downlink,
                pending: await (await import('./commandOutbox')).listCommands(
                    store,
                    { state: 'pending' },
                ),
            };
        },
    };
}
