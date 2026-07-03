// 同步的纯逻辑层：冲突分析、合并算法、时钟校准。
// 抽离自 components/LanSync.jsx，使其可独立于 React/UI 进行单元测试。
import { setClockOffset } from './clock';

// 同步服务器为固定地址（树莓派上的 Cloudflare Tunnel），HTTPS + 标准 443 端口，
// 不依赖公网 IP/IPv6/端口映射，运营商轮换前缀也不受影响。
export const DEFAULT_SERVER_URL = 'https://sync.hamhuo.top';

export const DEFAULT_CONFIG = { serverUrl: DEFAULT_SERVER_URL, autoSync: false, interval: 60 };

// 归一化服务器地址：补全协议、去掉末尾斜杠。裸主机名默认按 https 处理。
export function normalizeServerUrl(url) {
    const trimmed = (url || '').trim();
    if (!trimmed) return '';
    const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
    return withScheme.replace(/\/+$/, '');
}

// ── 统一实体抽象（Unified Entity）────────────────────────────────────────────
// events / goals / journals 底层结构不同（数组 vs 以日期为键的对象，字段名也不同），
// 但同步真正关心的只是同一组属性：唯一标识、净荷内容、最后修改时间、软删除标记。
// 把三者统一映射成 { id, payload, updatedAt, deletedAt } 后，可以共用同一套
// 比较 / 合并 / 冲突分析核心——不必再为每种数据各写一份、且要求彼此保持一致的
// 合并代码（这正是早先 journals/goals 漏同步、合并逻辑各自实现却又必须"逐字
// 一致"这整类 bug 的根源）。
//
// pickEntity / mergeEntities / analyzeEntities 是跨设备共享的核心比较逻辑，
// 必须与 sync-server/server.js 中的同名实现逐字一致：否则两端在"打破平局"时
// 可能选出不同的胜者，导致永远收敛不到同一结果（死锁式分歧）。

export function isAlive(e) { return !e.deletedAt; }

// updatedAt 相同时（典型情况：旧版迁移记录、或两端在同一毫秒各自独立创建/编辑），
// 用与"谁是 local/remote"无关的确定性方式选出胜者，保证两端最终收敛一致。
export function pickEntity(a, b) {
    const au = a?.updatedAt || 0, bu = b?.updatedAt || 0;
    if (au !== bu) return au > bu ? a : b;
    const ak = JSON.stringify({ payload: a?.payload, deletedAt: a?.deletedAt ?? null });
    const bk = JSON.stringify({ payload: b?.payload, deletedAt: b?.deletedAt ?? null });
    return ak >= bk ? a : b;
}

export function mergeEntities(local, remote) {
    const map = new Map(local.map(e => [e.id, e]));
    for (const e of remote) {
        const ex = map.get(e.id);
        map.set(e.id, ex ? pickEntity(ex, e) : e);
    }
    return Array.from(map.values());
}

// 冲突分析（updatedAt-wins + tombstone 感知 + 平局打破，与 mergeEntities 共用
// 同一套胜负判定，保证"预览看到的结果"与"实际合并的结果"完全一致）。
export function analyzeEntities(local, remote) {
    const localMap  = new Map(local.map(e  => [e.id, e]));
    const remoteMap = new Map(remote.map(e => [e.id, e]));
    const results = { added: [], removed: [], updated: [], deleted: [], synced: [], conflicted: [] };

    for (const [id, re] of remoteMap) {
        const le = localMap.get(id);
        if (!le) {
            if (isAlive(re)) results.added.push(re);
            continue; // remote tombstone for unknown id — no-op
        }
        const lu = le.updatedAt || 0, ru = re.updatedAt || 0;
        if (lu === ru) {
            const sameContent = JSON.stringify({ payload: le.payload, deletedAt: le.deletedAt ?? null })
                             === JSON.stringify({ payload: re.payload, deletedAt: re.deletedAt ?? null });
            if (sameContent) { results.synced.push(le); continue; }
        }
        if (pickEntity(le, re) === re) {
            if (!isAlive(re) && isAlive(le)) results.deleted.push({ local: le, remote: re }); // remote deleted it
            else results.updated.push({ local: le, remote: re });
        } else {
            if (!isAlive(le) && isAlive(re)) results.deleted.push({ local: le, remote: re }); // local deleted it (local wins)
            else results.conflicted.push({ local: le, remote: re });
        }
    }
    for (const [id, le] of localMap) {
        if (!remoteMap.has(id) && isAlive(le)) results.removed.push(le);
    }
    return results;
}

// ── 各数据类型 ↔ 统一实体 的适配层 ───────────────────────────────────────────
// events/goals 本身已是 { id, updatedAt, deletedAt, ... } 的数组，整条记录即 payload；
// journals 以日期为键、条目为 { text, updatedAt, deletedAt }，日期本身就是 id。
// fromEntity 对 events/goals 直接还原原始对象；journalFromEntity 额外把 id 还原成 date 字段。
const toEventEntity   = e => ({ id: e.id, payload: e, updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? null });
const toJournalEntity = (date, entry) => ({ id: date, payload: entry || {}, updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null });
const journalEntries  = obj => Object.entries(obj || {}).map(([date, entry]) => toJournalEntity(date, entry));
const fromEntity        = e => e.payload;
const journalFromEntity = e => ({ date: e.id, ...e.payload });

// 把 analyzeEntities 返回的统一实体结果，还原成各数据类型原本的展示形状
// （UI 需要读取 .title / .text / .date 等字段，详见 LanSync.jsx）。
function convertResults(results, toDisplay) {
    const pair = p => ({ local: toDisplay(p.local), remote: toDisplay(p.remote) });
    return {
        added:      results.added.map(toDisplay),
        removed:    results.removed.map(toDisplay),
        synced:     results.synced.map(toDisplay),
        updated:    results.updated.map(pair),
        deleted:    results.deleted.map(pair),
        conflicted: results.conflicted.map(pair),
    };
}

export function analyzeConflict(local, remote) {
    return convertResults(analyzeEntities(local.map(toEventEntity), remote.map(toEventEntity)), fromEntity);
}

export function analyzeJournalConflict(local, remote) {
    return convertResults(analyzeEntities(journalEntries(local), journalEntries(remote)), journalFromEntity);
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

// ── 合并算法（updatedAt-wins + tombstone 感知 + 平局打破）────────────────────
// events/goals/journals 三者现在都通过统一实体走同一条 mergeEntities 路径，
// 删除会写入 deletedAt+updatedAt，因此删除记录在合并时会和"更早"的存活记录
// 正常竞争，不会被回环恢复。
export function mergeEvents(local, remote) {
    return mergeEntities(local.map(toEventEntity), remote.map(toEventEntity)).map(fromEntity);
}

export function mergeJournals(local, remote) {
    const merged = mergeEntities(journalEntries(local), journalEntries(remote));
    const result = {};
    for (const e of merged) result[e.id] = fromEntity(e);
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
