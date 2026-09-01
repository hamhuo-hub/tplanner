// 历史覆盖记录(见 docs/sync-v3.md §5):冲突不再需要人工裁决 ——
// 被较晚命令覆盖的旧值只在快照安装历史里留档,供查看/恢复参考;
// 恢复本身走命令,不倒退快照版本。
export const HISTORY_KEY = 'sync-history';

export function appendSnapshotInstall(list, { version, stateHash, at = Date.now() }) {
    return [{ version, stateHash, at }, ...(list ?? [])].slice(0, 50);
}

export async function recordSnapshotInstall(store, { version, stateHash, at = Date.now() }) {
    const list = (await store.get(HISTORY_KEY)) ?? [];
    await store.set(HISTORY_KEY, appendSnapshotInstall(list, { version, stateHash, at }));
}

export async function listInstalls(store) {
    return (await store.get(HISTORY_KEY)) ?? [];
}
