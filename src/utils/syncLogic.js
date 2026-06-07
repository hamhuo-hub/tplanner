// LAN 同步的纯逻辑层：冲突分析、合并算法、时钟校准、连接历史持久化。
// 抽离自 components/LanSync.jsx，使其可独立于 React/UI 进行单元测试。
import { setClockOffset } from './clock';

export const DEFAULT_CONFIG = { peerIp: '', port: 37401, serverEnabled: false, autoSync: false, interval: 60 };

// ── 冲突分析（tombstone 感知）────────────────────────────────────────────────
export function isAlive(e) { return !e.deletedAt; }

// 在 updatedAt 相同（尤其是两端都为 0 的旧版迁移记录）时，
// 用与"谁是 local/remote"无关的确定性方式选出胜者，保证两端最终收敛一致。
// 必须与 sync-server/server.js 中的同名实现保持逐字一致。
export function pickJournalEntry(a, b) {
    const au = a?.updatedAt || 0, bu = b?.updatedAt || 0;
    if (au !== bu) return au > bu ? a : b;
    const ak = JSON.stringify({ text: a?.text || '', deletedAt: a?.deletedAt || null });
    const bk = JSON.stringify({ text: b?.text || '', deletedAt: b?.deletedAt || null });
    return ak >= bk ? a : b;
}

export function analyzeConflict(local, remote) {
    const localMap  = new Map(local.map(e  => [e.id, e]));
    const remoteMap = new Map(remote.map(e => [e.id, e]));

    const results = { added: [], removed: [], updated: [], deleted: [], synced: [], conflicted: [] };

    for (const [id, re] of remoteMap) {
        const le = localMap.get(id);
        if (!le) {
            if (isAlive(re)) results.added.push(re);
            // else: remote tombstone for unknown id — no-op
        } else if ((re.updatedAt || 0) > (le.updatedAt || 0)) {
            if (!isAlive(re) && isAlive(le)) {
                results.deleted.push({ local: le, remote: re }); // remote deleted it
            } else {
                results.updated.push({ local: le, remote: re });
            }
        } else if ((re.updatedAt || 0) < (le.updatedAt || 0)) {
            if (!isAlive(le) && isAlive(re)) {
                results.deleted.push({ local: le, remote: re }); // local deleted it (local wins)
            } else {
                results.conflicted.push({ local: le, remote: re });
            }
        } else {
            results.synced.push(le);
        }
    }
    for (const [id, le] of localMap) {
        if (!remoteMap.has(id) && isAlive(le)) results.removed.push(le);
    }
    return results;
}

// journals 用日期作为主键，条目为 { text, updatedAt, deletedAt }；
// 分析逻辑与 analyzeConflict 相同（updatedAt-wins + tombstone 感知 + 平局打破),
// 只是把 id 换成 date，把 title 换成 text。
export function analyzeJournalConflict(local, remote) {
    const localMap  = new Map(Object.entries(local || {}));
    const remoteMap = new Map(Object.entries(remote || {}));
    const results = { added: [], removed: [], updated: [], deleted: [], synced: [], conflicted: [] };
    const withDate = (date, e) => ({ date, ...e });

    for (const [date, re] of remoteMap) {
        const le = localMap.get(date);
        const r = withDate(date, re);
        if (!le) {
            if (isAlive(re)) results.added.push(r);
        } else {
            const l = withDate(date, le);
            const sameContent = (re.text || '') === (le.text || '') && !!re.deletedAt === !!le.deletedAt;
            if (sameContent) {
                results.synced.push(l);
            } else if (pickJournalEntry(le, re) === re) {
                if (!isAlive(re) && isAlive(le)) results.deleted.push({ local: l, remote: r });
                else results.updated.push({ local: l, remote: r });
            } else {
                if (!isAlive(le) && isAlive(re)) results.deleted.push({ local: l, remote: r });
                else results.conflicted.push({ local: l, remote: r });
            }
        }
    }
    for (const [date, le] of localMap) {
        if (!remoteMap.has(date) && isAlive(le)) results.removed.push(withDate(date, le));
    }
    return results;
}

// 统计「将被对端覆盖」的事件中，子任务（checklist）发生变化的数量 —— 仅计数，不展开列表
export function countSubtaskChanges(updated) {
    let events = 0, items = 0;
    for (const { local: l, remote: r } of updated) {
        const lm = new Map((l.checklist || []).map(i => [i.id, i]));
        const rm = new Map((r.checklist || []).map(i => [i.id, i]));
        let changed = 0;
        for (const id of new Set([...lm.keys(), ...rm.keys()])) {
            const li = lm.get(id), ri = rm.get(id);
            if (!li || !ri || li.text !== ri.text || li.completed !== ri.completed) changed++;
        }
        if (changed > 0) { events++; items += changed; }
    }
    return { events, items };
}

// ── 合并算法（updatedAt-wins + tombstone 感知）──────────────────────────────
export function mergeEvents(local, remote) {
    const map = new Map();
    for (const e of local)  map.set(e.id, e);
    for (const e of remote) {
        const ex = map.get(e.id);
        if (!ex || (e.updatedAt || 0) > (ex.updatedAt || 0)) map.set(e.id, e);
    }
    return Array.from(map.values());
}

// journals 合并：与 mergeEvents 相同的 updatedAt-wins + tombstone 策略。
// 条目格式 { text, updatedAt, deletedAt }；删除会写入 deletedAt+updatedAt，
// 因此删除记录在合并时会和"更早"的存活记录正常竞争，不会被回环恢复。
//
// updatedAt 相同时（典型情况：两端都是迁移自旧版纯字符串、值为 0 的记录，
// 但内容已各自独立改动而产生分歧）必须用与"谁是 local/remote"无关的、
// 两端结果一致的方式打破平局——否则 PC 端合并时偏向 PC 本地内容、
// server 端合并时偏向 server 本地内容，会导致两边永远收敛不到同一个结果，
// 形成"死锁式"分歧（这正是 server.js 中 mergeJournals 必须使用同一比较
// 函数 pickJournalEntry 的原因，必须与本文件保持完全一致）。
export function mergeJournals(local, remote) {
    const result = { ...(local || {}) };
    for (const [date, entry] of Object.entries(remote || {})) {
        const existing = result[date];
        result[date] = existing ? pickJournalEntry(existing, entry) : entry;
    }
    return result;
}

// ── 时钟校准 ─────────────────────────────────────────────────────────────────
// 设备间时钟不一致会让 updatedAt-wins 合并失去意义——时钟偏快的设备能
// 永久覆盖偏慢的设备，与编辑的实际先后顺序无关。每次同步前向对端请求
// 当前时间，估算本机时钟相对对端的偏移量并写入共享的 clock 模块；
// 之后所有新建的 updatedAt/deletedAt 改用 now()，得到的时间戳就近似
// 对端（同步服务器）的时钟，使跨设备时间戳重新具备可比性。
export async function syncClockOffset(base) {
    try {
        const t0 = Date.now();
        const res = await fetch(`${base}/tplanner/time`, { signal: AbortSignal.timeout(3000) });
        const t1 = Date.now();
        if (!res.ok) return;
        const { now: peerNow } = await res.json();
        if (!Number.isFinite(peerNow)) return;
        const rtt = t1 - t0;
        // 假设请求/响应耗时对称，对端收到响应时的时钟约为 peerNow + rtt/2
        setClockOffset((peerNow + rtt / 2) - t1);
    } catch (_) { /* clock sync is best-effort; fall back to local clock (offset 0) */ }
}

// ── 连接历史（localStorage）──────────────────────────────────────────────────
export function getHistory() {
    try { return JSON.parse(localStorage.getItem('tplanner_sync_history') || '[]'); }
    catch { return []; }
}
export function saveHistory(peer) {
    const list = getHistory().filter(h => !(h.ip === peer.ip && h.port === peer.port));
    list.unshift({ name: peer.name || peer.ip, ip: peer.ip, port: peer.port });
    localStorage.setItem('tplanner_sync_history', JSON.stringify(list.slice(0, 5)));
}
