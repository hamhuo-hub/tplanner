// 版本通知长轮询(见 docs/sync-v3.md §12/§14):
// 通知只含 version + hash,不含数据片段;收到新版本后由上层触发快照安装。
// 首版长轮询;桌面/Web 后续可换 SSE(不是当前瓶颈)。
import { loadSyncMeta } from './syncMeta';
import { protocolError, readJsonResponse } from './httpResponse';

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export function createNotificationClient({
    store,
    fetchFn,
    serverUrl,
    onNewVersion,
    waitMs = 25_000,
    waitFn = wait,
    randomFn = Math.random,
}) {
    let snapshotPollingFallback = false;

    async function pollOnce() {
        if (snapshotPollingFallback) {
            await waitFn(waitMs);
            return { notified: true, body: { fallback: 'snapshot-poll' } };
        }

        const meta = await loadSyncMeta(store);
        const after = meta.installedSnapshotVersion;
        const res = await fetchFn(
            `${serverUrl}/tplanner/v3/notifications?afterVersion=${after}&wait=${waitMs}`,
        );
        if (res.status === 404) {
            await readJsonResponse(res, 'snapshot notification request');
            snapshotPollingFallback = true;
            return { notified: true, body: { fallback: 'snapshot-poll' } };
        }
        if (!res.ok) throw new Error(`notifications request failed: ${res.status}`);
        const body = await readJsonResponse(res, 'snapshot notification request');
        const latestVersion = body?.latestVersion;
        if (typeof latestVersion !== 'number'
            || !Number.isSafeInteger(latestVersion)
            || latestVersion < 0) {
            throw protocolError('snapshot notification request returned an invalid version', res);
        }
        if (latestVersion > after) {
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
                let retryDelay = 2000;
                while (running) {
                    const result = await tickOnce();
                    if (!running) break;
                    if (result.error) {
                        const jittered = Math.round(retryDelay * (0.8 + randomFn() * 0.4));
                        await waitFn(jittered);
                        retryDelay = Math.min(retryDelay * 2, 30_000);
                    } else {
                        retryDelay = 2000;
                    }
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
