// 同步的纯逻辑层：三方对比（base 快照）、冲突分析、合并算法、时钟校准。
//
// ── 裁决模型（git 式三方合并的正确移植）─────────────────────────────────────
// git 不需要时间戳是因为它有共同祖先（merge base）。这里同理：客户端在每次
// 成功同步后保存「上次同步快照」（base，仅存 id → contentKey），下次同步做
// 三方对比：
//   本地==远端                → 已同步
//   仅本地相对 base 有改动     → 推送本地（自动）
//   仅远端相对 base 有改动     → 采用远端（自动）
//   两边都改且不同             → 真并发冲突 → 人工裁决（manual 桶）
//   没有 base（首次/新记录）   → 回退 updatedAt LWW + content-key 平局裁决
//
// 人工选择后给胜者盖新 updatedAt——服务器和安卓端继续跑纯 LWW，胜者天然
// 在全端胜出，三端收敛。未解决的冲突两边各保各的（本地不覆盖、推送用远端
// 原值），直到用户裁决，不会被自动同步冲掉。
//
// 曾试过 Lamport version 计数器（每次编辑 +1）替代时间戳：要求三端每一个
// 写入路径都严格递增，漏掉任何一个（安卓编辑、手表打点、便签窗口）就会
// "同版本异内容"走字节抽签，编辑被随机回滚。version 字段保留为元数据，
// 但不再参与裁决，也不进入内容比较键。
import { setClockOffset, now } from './clock';

export const DEFAULT_SERVER_URL = 'https://sync.hamhuo.top';
export const DEFAULT_CONFIG = { serverUrl: DEFAULT_SERVER_URL, autoSync: false, interval: 60 };

export function normalizeServerUrl(url) {
    const trimmed = (url || '').trim();
    if (!trimmed) return '';
    const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
    return withScheme.replace(/\/+$/, '');
}

export function isAlive(e) { return !e.deletedAt; }

// ── 稳定序列化（内容比较专用；安卓端 tieKey 与此逐字一致）────────────────────
export function stableStringify(v) {
    if (v === undefined) return undefined;
    if (v === null || typeof v !== 'object') return JSON.stringify(v);
    if (v instanceof Date) return JSON.stringify(v);
    if (Array.isArray(v)) return '[' + v.map(x => stableStringify(x) ?? 'null').join(',') + ']';
    const parts = [];
    for (const k of Object.keys(v).sort()) {
        const s = stableStringify(v[k]);
        if (s !== undefined) parts.push(JSON.stringify(k) + ':' + s);
    }
    return '{' + parts.join(',') + '}';
}

// ── 规范形 ────────────────────────────────────────────────────────────────────
// version 必须从 payload 里剔除：并非所有写入方都维护它（安卓/手表打点/便签
// 只更新 updatedAt），若进入比较键，同一内容会因 version 有无而判为不同。
const toIsoMs = (v) => {
    const t = new Date(v).getTime();
    return Number.isFinite(t) ? new Date(t).toISOString() : v;
};

export function canonicalEvent(e) {
    const c = { ...e,
        type: e.type || 'event', start: toIsoMs(e.start), end: toIsoMs(e.end),
        note: e.note || '', timezone: e.timezone || '', groupId: e.groupId || '',
        colorId: e.colorId ?? 0, completed: e.completed ?? false,
        checklist: e.checklist ?? [], recurrenceType: e.recurrenceType || 'none',
        recurrenceCount: e.recurrenceCount || 1,
        updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? 0,
    };
    delete c.version;
    return c;
}

export function canonicalGoal(g) {
    const c = { ...g,
        note: g.note ?? '', icon: g.icon ?? '', order: g.order ?? 0,
        updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt ?? 0,
    };
    delete c.version;
    return c;
}

function canonicalJournalEntry(entry) {
    const e = entry || {};
    return { text: e.text || '', updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? null };
}

// 内容比较键：payload 应已是规范形，稳定序列化后即可字节比较。
export function contentKey(e) {
    return stableStringify({ payload: e?.payload, deletedAt: e?.deletedAt ?? null });
}

// ── LWW 兜底裁决（updatedAt + content-key 平局；与服务器/安卓逐字一致）──────
export function pickEntity(a, b) {
    const au = a?.updatedAt || 0, bu = b?.updatedAt || 0;
    if (au !== bu) return au > bu ? a : b;
    return contentKey(a) >= contentKey(b) ? a : b;
}

export function mergeEntities(local, remote) {
    const map = new Map(local.map(e => [e.id, e]));
    for (const e of remote) {
        const ex = map.get(e.id);
        map.set(e.id, ex ? pickEntity(ex, e) : e);
    }
    return Array.from(map.values());
}

// ── 三方冲突分析 ──────────────────────────────────────────────────────────────
// baseKeys: { id → contentKey }，上次成功同步后的快照；null/缺项时回退 LWW。
// 返回桶：
//   added      远端新增 → 拉取          removed  本地独有 → 推送
//   updated    采用远端（远端改/更新）   deleted  墓碑传播（一端已删）
//   conflicted 保留本地并推送（本地改）  synced   一致
//   manual     真并发冲突 [{local, remote}] → 需人工裁决
export function analyzeEntities(local, remote, baseKeys = null) {
    const localMap  = new Map(local.map(e => [e.id, e]));
    const remoteMap = new Map(remote.map(e => [e.id, e]));
    const r = { added: [], removed: [], updated: [], deleted: [], synced: [], conflicted: [], manual: [] };

    for (const [id, re] of remoteMap) {
        const le = localMap.get(id);
        if (!le) { if (isAlive(re)) r.added.push(re); continue; }

        const kl = contentKey(le), kr = contentKey(re);
        if (kl === kr) { r.synced.push(le); continue; }

        const kb = baseKeys ? baseKeys[id] : undefined;
        if (kb !== undefined && kl === kb) {
            // 仅远端有改动 → 采用远端
            if (!isAlive(re) && isAlive(le)) r.deleted.push({ local: le, remote: re });
            else r.updated.push({ local: le, remote: re });
        } else if (kb !== undefined && kr === kb) {
            // 仅本地有改动 → 保留本地并推送
            r.conflicted.push({ local: le, remote: re });
        } else if (kb !== undefined) {
            // 两边都改且不同 → 人工裁决
            r.manual.push({ local: le, remote: re });
        } else {
            // 无基线（首次同步 / 新记录两端各自出现）→ LWW 自动裁决
            if (pickEntity(le, re) === re) {
                if (!isAlive(re) && isAlive(le)) r.deleted.push({ local: le, remote: re });
                else r.updated.push({ local: le, remote: re });
            } else {
                if (!isAlive(le) && isAlive(re)) r.deleted.push({ local: le, remote: re });
                else r.conflicted.push({ local: le, remote: re });
            }
        }
    }
    for (const [id, le] of localMap) {
        if (!remoteMap.has(id) && isAlive(le)) r.removed.push(le);
    }
    return r;
}

// ── 三方合并 ──────────────────────────────────────────────────────────────────
// resolutions: { id → 'local' | 'remote' }，人工裁决结果。
// 返回:
//   merged      写回本地的实体（未解决的冲突保留本地版本）
//   pushData    推送到服务器的实体（未解决的冲突推远端原值 → 不覆盖对方）
//   newBaseKeys 下次同步的基线（未解决的冲突保留旧基线 → 下次仍报冲突）
//   unresolved  未解决冲突数
export function mergeEntitiesWithBase(local, remote, baseKeys = null, resolutions = {}) {
    const analysis = analyzeEntities(local, remote, baseKeys);
    const localMap  = new Map(local.map(e => [e.id, e]));
    const remoteMap = new Map(remote.map(e => [e.id, e]));
    const manualIds = new Set(analysis.manual.map(p => p.local.id));

    const merged = [], pushOverride = new Map(), newBaseKeys = {};
    let unresolved = 0;

    for (const id of new Set([...localMap.keys(), ...remoteMap.keys()])) {
        const le = localMap.get(id), re = remoteMap.get(id);
        let winner;
        if (!le) winner = re;
        else if (!re) winner = le;
        else if (manualIds.has(id)) {
            const choice = resolutions[id];
            if (choice === 'local' || choice === 'remote') {
                // 胜者盖新 updatedAt：在服务器/安卓的纯 LWW 里天然胜出，全端收敛
                const src = choice === 'local' ? le : re;
                const ts = now();
                winner = { ...src, updatedAt: ts, payload: { ...src.payload, updatedAt: ts } };
            } else {
                // 未裁决：本地保留本地，推送用远端原值（互不覆盖），基线不动
                unresolved++;
                merged.push(le);
                pushOverride.set(id, re);
                if (baseKeys && baseKeys[id] !== undefined) newBaseKeys[id] = baseKeys[id];
                continue;
            }
        } else {
            // 自动路径：三方规则已把"该采用谁"表达在 analyzeEntities 里，
            // 这里用等价判定重建——有基线时按基线，无基线时 LWW。
            const kb = baseKeys ? baseKeys[id] : undefined;
            const kl = contentKey(le), kr = contentKey(re);
            if (kl === kr) winner = le;
            else if (kb !== undefined && kl === kb) winner = re;
            else if (kb !== undefined && kr === kb) winner = le;
            else winner = pickEntity(le, re);
        }
        merged.push(winner);
        newBaseKeys[winner.id] = contentKey(winner);
    }

    const pushData = merged.map(e => pushOverride.get(e.id) ?? e);
    return { merged, pushData, newBaseKeys, unresolved, analysis };
}

// ── 实体映射 ─────────────────────────────────────────────────────────────────
const toEventEntity   = e => ({ id: e.id, payload: canonicalEvent(e),   updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt || null });
const toGoalEntity    = g => ({ id: g.id, payload: canonicalGoal(g),    updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt || null });
const toJournalEntity = (date, entry) => ({ id: date, payload: canonicalJournalEntry(entry), updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null });
const fromEntity        = e => e.payload;
const journalFromEntity = e => ({ date: e.id, ...e.payload });

// 兼容旧调用（分析函数无 base 时 = 纯 LWW 分析）
export function analyzeConflict(local, remote) {
    return convertResults(analyzeEntities(local.map(toEventEntity), remote.map(toEventEntity)), fromEntity);
}
export function analyzeGoalConflict(local, remote) {
    return convertResults(analyzeEntities(local.map(toGoalEntity), remote.map(toGoalEntity)), fromEntity);
}
export function analyzeJournalConflict(local, remote) {
    const je = obj => Object.entries(obj || {}).map(([d, e]) => toJournalEntity(d, e));
    return convertResults(analyzeEntities(je(local), je(remote)), journalFromEntity);
}
export function mergeEvents(local, remote) {
    return mergeEntities(local.map(toEventEntity), remote.map(toEventEntity)).map(fromEntity);
}
export function mergeGoals(local, remote) {
    return mergeEntities(local.map(toGoalEntity), remote.map(toGoalEntity)).map(fromEntity);
}
export function mergeJournals(local, remote) {
    const je = obj => Object.entries(obj || {}).map(([d, e]) => toJournalEntity(d, e));
    const merged = mergeEntities(je(local), je(remote));
    const result = {};
    for (const e of merged) result[e.id] = fromEntity(e);
    return result;
}

// ── 时钟校准 ─────────────────────────────────────────────────────────────────
export async function syncClockOffset(base) {
    try {
        const t0 = Date.now();
        const res = await fetch(`${base}/tplanner/time`, { signal: AbortSignal.timeout(3000) });
        const t1 = Date.now();
        if (!res.ok) return;
        const { now: peerNow } = await res.json();
        if (!Number.isFinite(peerNow)) return;
        const rtt = t1 - t0;
        setClockOffset((peerNow + rtt / 2) - t1);
    } catch (_) { /* best-effort */ }
}

// ── SyncAdapter ──────────────────────────────────────────────────────────────

export function convertResults(results, toDisplay) {
    // pair 带上 id，人工裁决 UI 需要用 id 记录用户的选择
    const pair = p => ({ id: p.local.id, local: toDisplay(p.local), remote: toDisplay(p.remote) });
    return { added: results.added.map(toDisplay), removed: results.removed.map(toDisplay),
             synced: results.synced.map(toDisplay), updated: results.updated.map(pair),
             deleted: results.deleted.map(pair), conflicted: results.conflicted.map(pair),
             manual: (results.manual || []).map(pair) };
}

export function createSyncAdapter(config) {
    const { type, endpoint, isRequired = false, unitName = '条',
            toEntity, fromEntity: fromEnt, localToEntities, entitiesToLocal, itemLabel,
            remoteToEntities } = config;
    const _localToEntities  = localToEntities  || (local => (Array.isArray(local) ? local : []).map(toEntity));
    const _entitiesToLocal  = entitiesToLocal  || (entities => entities.map(fromEnt));
    const _remoteToEntities = remoteToEntities || (remote => (Array.isArray(remote) ? remote : []).map(toEntity));
    return {
        type, endpoint, isRequired, unitName,
        toEntity, fromEntity: fromEnt,
        localToEntities: _localToEntities, entitiesToLocal: _entitiesToLocal,
        remoteToEntities: _remoteToEntities,
        itemLabel: itemLabel || (item => item?.title ?? item?.text ?? ''),
        analyze(local, remote, baseKeys = null) {
            return convertResults(analyzeEntities(_localToEntities(local), _remoteToEntities(remote), baseKeys), fromEnt);
        },
        // 三方合并：返回 { merged(本地形状), pushData(本地形状), newBaseKeys, unresolved }
        mergeWithBase(local, remote, baseKeys = null, resolutions = {}) {
            const r = mergeEntitiesWithBase(_localToEntities(local), _remoteToEntities(remote), baseKeys, resolutions);
            return {
                merged: _entitiesToLocal(r.merged),
                pushData: _entitiesToLocal(r.pushData),
                newBaseKeys: r.newBaseKeys,
                unresolved: r.unresolved,
            };
        },
    };
}

export async function fetchAndAnalyze(adapter, serverUrl, localData, baseKeys = null) {
    try {
        const res = await fetch(`${serverUrl}${adapter.endpoint}`, { method: 'GET', signal: AbortSignal.timeout(10000) });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const remoteData = await res.json();
        return { adapter, remoteData, analysis: adapter.analyze(localData, remoteData, baseKeys) };
    } catch (_) { return null; }
}

// 拉取 → 三方合并 → 推送。resolutions 为该 adapter 的人工裁决 { id → 'local'|'remote' }。
export async function syncAndPush(adapter, serverUrl, localData, baseKeys = null, resolutions = {}) {
    const base = normalizeServerUrl(serverUrl);
    try {
        const res = await fetch(`${base}${adapter.endpoint}`, { method: 'GET', signal: AbortSignal.timeout(10000) });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const remoteData = await res.json();
        const r = adapter.mergeWithBase(localData, remoteData, baseKeys, resolutions);
        await fetch(`${base}${adapter.endpoint}`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(r.pushData), signal: AbortSignal.timeout(10000),
        });
        return r;
    } catch (_) { return null; }
}

// ── 内置 adapters ────────────────────────────────────────────────────────────
const _je = ([date, entry]) => toJournalEntity(date, entry);

const _eventsAdapter = createSyncAdapter({
    type: 'events', endpoint: '/tplanner/events', isRequired: true, unitName: '条',
    toEntity: toEventEntity, fromEntity, itemLabel: e => e?.title ?? '',
});

const _goalsAdapter = createSyncAdapter({
    type: 'goals', endpoint: '/tplanner/goals', unitName: '个',
    toEntity: toGoalEntity, fromEntity, itemLabel: g => g?.title ?? '',
});

const _journalsAdapter = createSyncAdapter({
    type: 'journals', endpoint: '/tplanner/journals', unitName: '篇',
    toEntity: _je, fromEntity: journalFromEntity,
    localToEntities: obj => Object.entries(obj || {}).map(_je),
    remoteToEntities: obj => Object.entries(obj || {}).map(_je),
    entitiesToLocal: entities => { const r = {}; for (const e of entities) r[e.id] = { ...e.payload }; return r; },
    itemLabel: item => { const t = (item?.text || '').replace(/\s+/g, ' ').trim().slice(0, 20); return t ? `${item.date} · ${t}${item.text?.length > 20 ? '…' : ''}` : item?.date ?? ''; },
});

export const BUILTIN_ADAPTERS = { events: _eventsAdapter, goals: _goalsAdapter, journals: _journalsAdapter };
