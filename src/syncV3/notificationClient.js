// 版本通知长轮询(见 docs/sync-v3.md §12/§14):
// 通知只含 version + hash,不含数据片段;收到新版本后由上层触发快照安装。
// 首版长轮询;桌面/Web 后续可换 SSE(不是当前瓶颈)。
import { loadSyncMeta } from './syncMeta';

export function createNotificationClient({ store, fetchFn, serverUrl, onNewVersion, waitMs = 25_000 }) {
    async function pollOnce() {
        const meta = await loadSyncMeta(store);
        const after = meta.installedSnapshotVersion;
        const res = await fetchFn(
            `${serverUrl}/tplanner/v3/notifications?afterVersion=${after}&wait=${waitMs}`,
        );
        if (!res.ok) throw new Error(`notifications request failed: ${res.status}`);
        const body = await res.json();
        if (Number(body.latestVersion) > after) {
            return { notified: true, body };
        }
        return { notified: false, body };
    }

    let running = false;
    let loop = null;

    /** 循环体单次执行:吞掉网络错误,返回结果。start() 只负责反复调用它。 */
    async function tickOnce() {
        try {
            const { notified } = await pollOnce();
            if (notified) await onNewVersion?.();
            return { notified, error: null };
        } catch (err) {
            return { notified: false, error: err };
        }
    }

    return {
        pollOnce,
        tickOnce,
        /** 前台/启动时调用一次,收到新版本立即安装,返回是否有新版本。 */
        async pollAndSync(syncToLatest) {
            const { notified, body } = await pollOnce();
            if (notified) await syncToLatest();
            return { notified, body };
        },
        start() {
            if (running) return;
            running = true;
            const tick = async () => {
                while (running) {
                    await tickOnce();
                }
            };
            loop = tick().catch(() => {});
        },
        stop() {
            running = false;
            loop = null;
        },
    };
}
