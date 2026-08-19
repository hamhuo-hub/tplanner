// 批次上传器(见 docs/sync-v3.md §12/§16):
//   - 前一批未获 BROKER_PERSISTED 不上传下一批(串行排空);
//   - 202 只表示 broker 已持久接收 → 命令标记 uploaded,仍留在 outbox;
//   - 回执确认后才删除 outbox 条目(不变量 #10)。
import { v7 as uuidv7 } from 'uuid';
import { loadSyncMeta } from './syncMeta';
import { listCommands, markUploaded, applyReceipts } from './commandOutbox';

export function createUploader({ store, fetchFn, serverUrl }) {
    async function postBatch(commands, deviceId) {
        const batchId = uuidv7();
        const batch = {
            protocolVersion: 3,
            batchId,
            deviceId,
            firstClientSequence: commands[0].clientSequence,
            lastClientSequence: commands[commands.length - 1].clientSequence,
            commands: commands.map(({ state, ...command }) => command),
        };
        const res = await fetchFn(`${serverUrl}/tplanner/v3/command-batches`, {
            method: 'POST',
            headers: { 'content-type': 'application/json', 'idempotency-key': batchId },
            body: JSON.stringify(batch),
        });
        if (res.status === 202) {
            const body = await res.json();
            await markUploaded(store, commands.map((c) => c.clientSequence));
            return { batch, body };
        }
        const body = await res.json().catch(() => ({}));
        const err = new Error(`command batch rejected: ${res.status} ${body?.error ?? ''}`);
        err.status = res.status;
        err.body = body;
        throw err;
    }

    return {
        /** 排空一轮:上传一批 pending 命令并收集回执。返回上传条数。 */
        async pump({ maxBatch = 100 } = {}) {
            if (!serverUrl) throw new Error('sync server url not configured');
            const meta = await loadSyncMeta(store);
            const pending = await listCommands(store, { state: 'pending', limit: maxBatch });
            if (pending.length === 0) return { uploaded: 0 };

            await postBatch(pending, meta.deviceId);
            return { uploaded: pending.length };
        },

        /** 拉取回执并据此删除已确认的 outbox 条目。 */
        async collectReceipts() {
            const meta = await loadSyncMeta(store);
            const res = await fetchFn(
                `${serverUrl}/tplanner/v3/receipts?deviceId=${encodeURIComponent(meta.deviceId)}&afterClientSequence=0`,
            );
            if (!res.ok) throw new Error(`receipts request failed: ${res.status}`);
            const body = await res.json();
            const applied = await applyReceipts(store, body.results ?? []);
            return { acceptedThrough: body.acceptedThrough ?? 0, applied };
        },

        /** 手动同步入口(§16):上传 → 等回执 → 返回待快照安装的状态。 */
        async flush() {
            let total = 0;
            for (;;) {
                const { uploaded } = await this.pump();
                if (uploaded === 0) break;
                total += uploaded;
            }
            await this.collectReceipts();
            return { uploaded: total };
        },
    };
}
