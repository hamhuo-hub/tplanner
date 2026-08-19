// 同步元数据:deviceId(每次安装新生成、永不随备份恢复,见 docs/sync-v3.md §11)、
// clientSequence 分配器、已安装快照指针、serverInstanceId。
import { v7 as uuidv7 } from 'uuid';

const META_KEY = 'meta';

export async function loadSyncMeta(store) {
    let meta = await store.get(META_KEY);
    if (!meta) {
        meta = {
            deviceId: `desktop-${uuidv7()}`,
            nextClientSequence: 1,
            installedSnapshotVersion: 0,
            installedSnapshotHash: null,
            serverInstanceId: null,
        };
        await store.set(META_KEY, meta);
    }
    return meta;
}

export async function updateSyncMeta(store, patch) {
    const meta = await loadSyncMeta(store);
    const next = { ...meta, ...patch };
    await store.set(META_KEY, next);
    return next;
}
