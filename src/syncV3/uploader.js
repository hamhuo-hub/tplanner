// 批次上传器(见 docs/sync-v3.md §12/§16):
//   - 前一批未获 BROKER_PERSISTED 不上传下一批(串行排空);
//   - 202 只表示 broker 已持久接收 → 命令标记 uploaded,仍留在 outbox;
//   - 回执先持久化；覆盖该回执的中央快照安装后才原子删除 outbox(不变量 #10)。
import { v7 as uuidv7 } from 'uuid';
import { loadSyncMeta } from './syncMeta';
import { listCommands, markUploaded, applyReceipts } from './commandOutbox';
import { protocolError, readJsonResponse } from './httpResponse';

const RECEIPT_STATUSES = new Set([
    'APPLIED',
    'NOOP',
    'REJECTED',
    'ENTITY_DELETED',
    'ID_ALREADY_EXISTS',
    'SCHEMA_UNSUPPORTED',
    'SEQUENCE_GAP',
]);

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
        const body = await readJsonResponse(res, 'command batch upload');
        if (res.status === 202) {
            if (!body || typeof body !== 'object' || body.state !== 'BROKER_PERSISTED'
                || body.batchId !== batch.batchId) {
                throw protocolError('command batch upload returned an invalid acknowledgement', res);
            }
            await markUploaded(store, commands.map((c) => c.clientSequence));
            return { batch, body };
        }
        const err = new Error(`command batch rejected: ${res.status} ${body?.error ?? ''}`);
        err.status = res.status;
        err.body = body;
        throw err;
    }

    return {
        /** 排空一轮:上传一批 pending 命令并收集回执。返回上传条数。 */
        async pump({ maxBatch = 100 } = {}) {
            // An empty base URL intentionally means same-origin for the web client.
            if (serverUrl == null) throw new Error('sync server url not configured');
            const meta = await loadSyncMeta(store);
            const pending = await listCommands(store, { state: 'pending', limit: maxBatch });
            if (pending.length === 0) return { uploaded: 0 };

            await postBatch(pending, meta.deviceId);
            return { uploaded: pending.length };
        },

        /** 拉取并持久化回执；outbox 仅由覆盖它的快照安装/重基事务删除。 */
        async collectReceipts() {
            const meta = await loadSyncMeta(store);
            let after = 0;
            let acceptedThrough = 0;
            let applied = 0;

            for (;;) {
                const res = await fetchFn(
                    `${serverUrl}/tplanner/v3/receipts?deviceId=${encodeURIComponent(meta.deviceId)}&afterClientSequence=${after}`,
                );
                if (!res.ok) throw new Error(`receipts request failed: ${res.status}`);
                const body = await readJsonResponse(res, 'command receipts request');
                if (!body || typeof body !== 'object' || !Array.isArray(body.results)
                    || !Number.isSafeInteger(body.acceptedThrough) || body.acceptedThrough < 0) {
                    throw protocolError('command receipts request returned an invalid payload', res);
                }

                let previous = after;
                for (const receipt of body.results) {
                    if (!Number.isSafeInteger(receipt?.clientSequence)
                        || receipt.clientSequence <= previous
                        || receipt.clientSequence > body.acceptedThrough
                        || typeof receipt.commandId !== 'string'
                        || receipt.commandId === ''
                        || !RECEIPT_STATUSES.has(receipt.status)
                        || !Number.isSafeInteger(receipt.brokerSequence)
                        || receipt.brokerSequence < 1
                        || (receipt.snapshotVersion != null
                            && (!Number.isSafeInteger(receipt.snapshotVersion)
                                || receipt.snapshotVersion < 1))) {
                        throw protocolError('command receipts request returned an invalid receipt', res);
                    }
                    const command = await store.get(`cmd:${receipt.clientSequence}`);
                    if (command && command.commandId !== receipt.commandId) {
                        throw protocolError('command receipt does not match the local outbox', res);
                    }
                    previous = receipt.clientSequence;
                }

                acceptedThrough = Math.max(acceptedThrough, body.acceptedThrough);
                applied = Math.max(applied, await applyReceipts(store, body.results));
                if (body.results.length === 0) break;
                after = body.results.at(-1).clientSequence;
                if (after >= body.acceptedThrough) break;
            }

            return { acceptedThrough, applied };
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
