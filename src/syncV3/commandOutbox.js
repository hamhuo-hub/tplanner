// 命令 outbox(见 docs/sync-v3.md §9):本地提交即持久化,生命周期:
//   pending → uploaded(BROKER_PERSISTED)→ 中央回执/快照确认后删除。
// 客户端拿到 BROKER_PERSISTED 前绝不删除(不变量 #10/#12)。
import { v7 as uuidv7 } from 'uuid';
import { loadSyncMeta, updateSyncMeta } from './syncMeta';

const CMD_PREFIX = 'cmd:';
const RECEIPT_PREFIX = 'receipt:';
const TERMINAL_RECEIPT_STATUSES = new Set([
    'APPLIED',
    'NOOP',
    'REJECTED',
    'ENTITY_DELETED',
    'ID_ALREADY_EXISTS',
    'SCHEMA_UNSUPPORTED',
]);

export async function appendCommands(store, commands) {
    const meta = await loadSyncMeta(store);
    const base = meta.nextClientSequence;
    const stamped = commands.map((c, i) => ({
        ...c,
        commandId: c.commandId ?? uuidv7(),
        clientSequence: base + i,
        state: 'pending',
    }));
    for (const c of stamped) {
        await store.set(`${CMD_PREFIX}${c.clientSequence}`, c);
    }
    await updateSyncMeta(store, { nextClientSequence: base + stamped.length });
    return stamped;
}

export async function listCommands(store, { state = 'pending', limit = 100 } = {}) {
    const rows = await store.entries(CMD_PREFIX);
    const states = new Set(Array.isArray(state) ? state : [state]);
    return rows
        .map(([, c]) => c)
        .filter((c) => states.has(c.state))
        .sort((a, b) => a.clientSequence - b.clientSequence)
        .slice(0, limit);
}

export async function markUploaded(store, clientSequences) {
    for (const seq of clientSequences) {
        const key = `${CMD_PREFIX}${seq}`;
        const cmd = await store.get(key);
        if (cmd) await store.set(key, { ...cmd, state: 'uploaded' });
    }
}

// 回执只持久化。命令必须继续参与 optimistic overlay，直到安装的中央快照以
// snapshotVersion 或 brokerSequence 明确覆盖该回执；删除由快照安装事务完成。
export async function applyReceipts(store, receipts) {
    let through = 0;
    for (const r of receipts ?? []) {
        await store.set(`${RECEIPT_PREFIX}${r.clientSequence}`, r);
        const commandKey = `${CMD_PREFIX}${r.clientSequence}`;
        const command = await store.get(commandKey);
        if (command?.commandId === r.commandId && r.status === 'SEQUENCE_GAP') {
            await store.set(commandKey, { ...command, state: 'pending' });
        }
        if (r.clientSequence > through) through = r.clientSequence;
    }
    return through;
}

export function isTerminalReceipt(status) {
    return TERMINAL_RECEIPT_STATUSES.has(status);
}

export async function getReceipt(store, clientSequence) {
    return store.get(`${RECEIPT_PREFIX}${clientSequence}`);
}
