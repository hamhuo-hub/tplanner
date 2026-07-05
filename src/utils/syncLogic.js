// 同步的纯逻辑层：冲突分析（版本裁决）、合并算法、时钟校准。
import { setClockOffset } from './clock';

export const DEFAULT_SERVER_URL = 'https://sync.hamhuo.top';
export const DEFAULT_CONFIG = { serverUrl: DEFAULT_SERVER_URL, autoSync: false, interval: 60 };

export function normalizeServerUrl(url) {
    const trimmed = (url || '').trim();
    if (!trimmed) return '';
    const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`;
    return withScheme.replace(/\/+$/, '');
}

// ── 统一实体抽象（Unified Entity）────────────────────────────────────────────
// { id, payload, version, updatedAt, deletedAt }
// version 是 Lamport 逻辑时钟：每次本地编辑 +1，合并后取 max。

export function isAlive(e) { return !e.deletedAt; }

// ── 稳定序列化 ────────────────────────────────────────────────────────────────
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
const toIsoMs = (v) => {
    const t = new Date(v).getTime();
    return Number.isFinite(t) ? new Date(t).toISOString() : v;
};

export function canonicalEvent(e) {
    return { ...e,
        type: e.type || 'event', start: toIsoMs(e.start), end: toIsoMs(e.end),
        note: e.note || '', timezone: e.timezone || '', groupId: e.groupId || '',
        colorId: e.colorId ?? 0, completed: e.completed ?? false,
        checklist: e.checklist ?? [], recurrenceType: e.recurrenceType || 'none',
        recurrenceCount: e.recurrenceCount || 1,
        version: e.version || 0, updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? 0,
    };
}

export function canonicalGoal(g) {
    return { ...g,
        note: g.note ?? '', icon: g.icon ?? '', order: g.order ?? 0,
        version: g.version || 0, updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt ?? 0,
    };
}

// 内容比较键：payload 应已是规范形，稳定序列化后即可字节比较。
function contentKey(e) {
    return stableStringify({ payload: e?.payload, deletedAt: e?.deletedAt ?? null });
}

// ── 版本裁决（Lamport 逻辑时钟）─────────────────────────────────────────────
// 内容相同 → 同一版本。内容不同时：高版本自动胜出；同版本用 content-key 打破平局。
// 必须与 sync-server/server.js 中的同名实现逐字一致。
export function pickEntity(a, b) {
    if (contentKey(a) === contentKey(b)) return a;
    const av = a?.version || 0, bv = b?.version || 0;
    if (av !== bv) return av > bv ? a : b;
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

// ── 冲突分析（version-based + tombstone 感知）───────────────────────────────
export function analyzeEntities(local, remote) {
    const localMap  = new Map(local.map(e => [e.id, e]));
    const remoteMap = new Map(remote.map(e => [e.id, e]));
    const r = { added: [], removed: [], updated: [], deleted: [], synced: [], conflicted: [] };

    for (const [id, re] of remoteMap) {
        const le = localMap.get(id);
        if (!le) { if (isAlive(re)) r.added.push(re); continue; }
        // 内容相同 → 已同步
        if (contentKey(le) === contentKey(re)) { r.synced.push(le); continue; }
        // 版本相同、内容不同 → 真正并发冲突，用户裁决
        if ((le.version || 0) === (re.version || 0)) {
            r.conflicted.push({ local: le, remote: re }); continue;
        }
        // 版本不同 → 高版本自动胜出
        if (pickEntity(le, re) === re) {
            if (!isAlive(re) && isAlive(le)) r.deleted.push({ local: le, remote: re });
            else r.updated.push({ local: le, remote: re });
        } else {
            if (!isAlive(le) && isAlive(re)) r.deleted.push({ local: le, remote: re });
            else r.conflicted.push({ local: le, remote: re });
        }
    }
    for (const [id, le] of localMap) {
        if (!remoteMap.has(id) && isAlive(le)) r.removed.push(le);
    }
    return r;
}

// ── 实体映射（从 payload 提取 version 到顶层）─────────────────────────────
const ev = (payload) => payload?.version || 0;

const toEventEntity   = e => ({ id: e.id, payload: canonicalEvent(e),   version: ev(e), updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt || null });
const toGoalEntity    = g => ({ id: g.id, payload: canonicalGoal(g),    version: ev(g), updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt || null });
const toJournalEntity = (date, entry) => ({ id: date, payload: entry || {}, version: ev(entry), updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null });
const journalEntries  = obj => Object.entries(obj || {}).map(([date, entry]) => toJournalEntity(date, entry));
const fromEntity        = e => e.payload;
const journalFromEntity = e => ({ date: e.id, ...e.payload });

export function analyzeConflict(local, remote) {
    return convertResults(analyzeEntities(local.map(toEventEntity), remote.map(toEventEntity)), fromEntity);
}
export function analyzeGoalConflict(local, remote) {
    return convertResults(analyzeEntities(local.map(toGoalEntity), remote.map(toGoalEntity)), fromEntity);
}
export function analyzeJournalConflict(local, remote) {
    return convertResults(analyzeEntities(journalEntries(local), journalEntries(remote)), journalFromEntity);
}

export function mergeEvents(local, remote) {
    return mergeEntities(local.map(toEventEntity), remote.map(toEventEntity)).map(fromEntity);
}
export function mergeGoals(local, remote) {
    return mergeEntities(local.map(toGoalEntity), remote.map(toGoalEntity)).map(fromEntity);
}
export function mergeJournals(local, remote) {
    const merged = mergeEntities(journalEntries(local), journalEntries(remote));
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
    const pair = p => ({ local: toDisplay(p.local), remote: toDisplay(p.remote) });
    return { added: results.added.map(toDisplay), removed: results.removed.map(toDisplay),
             synced: results.synced.map(toDisplay), updated: results.updated.map(pair),
             deleted: results.deleted.map(pair), conflicted: results.conflicted.map(pair) };
}

export function createSyncAdapter(config) {
    const { type, endpoint, isRequired = false, unitName = '条',
            toEntity, fromEntity, localToEntities, entitiesToLocal, itemLabel,
            remoteToEntities } = config;
    const _localToEntities = localToEntities || (local => (Array.isArray(local) ? local : []).map(toEntity));
    const _entitiesToLocal = entitiesToLocal || (entities => entities.map(fromEntity));
    const _remoteToEntities = remoteToEntities || (remote => (Array.isArray(remote) ? remote : []).map(toEntity));
    return {
        type, endpoint, isRequired, unitName, toEntity, fromEntity,
        localToEntities: _localToEntities, entitiesToLocal: _entitiesToLocal,
        itemLabel: itemLabel || (item => item?.title ?? item?.text ?? ''),
        analyze(local, remote) {
            return convertResults(analyzeEntities(_localToEntities(local), _remoteToEntities(remote)), fromEntity);
        },
        merge(local, remote) {
            return _entitiesToLocal(mergeEntities(_localToEntities(local), _remoteToEntities(remote)));
        },
    };
}

export async function fetchAndAnalyze(adapter, serverUrl, localData) {
    try {
        const res = await fetch(`${serverUrl}${adapter.endpoint}`, { method: 'GET', signal: AbortSignal.timeout(10000) });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const remoteData = await res.json();
        return { adapter, remoteData, analysis: adapter.analyze(localData, remoteData) };
    } catch (_) { return null; }
}

export async function syncAndPush(adapter, serverUrl, localData) {
    const base = normalizeServerUrl(serverUrl);
    try {
        const res = await fetch(`${base}${adapter.endpoint}`, { method: 'GET', signal: AbortSignal.timeout(10000) });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const remoteData = await res.json();
        const merged = adapter.merge(localData, remoteData);
        await fetch(`${base}${adapter.endpoint}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(merged), signal: AbortSignal.timeout(10000) });
        return merged;
    } catch (_) { return null; }
}

// ── 内联 fallback adapters ──────────────────────────────────────────────────
const _je = ([date, entry]) => ({ id: date, payload: entry || {}, version: ev(entry), updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null });

const _eventsAdapter = createSyncAdapter({
    type: 'events', endpoint: '/tplanner/events', isRequired: true, unitName: '条',
    toEntity: e => ({ id: e.id, payload: canonicalEvent(e), version: ev(e), updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt || null }),
    fromEntity: e => e.payload, itemLabel: e => e?.title ?? '',
});

const _goalsAdapter = createSyncAdapter({
    type: 'goals', endpoint: '/tplanner/goals', unitName: '个',
    toEntity: g => ({ id: g.id, payload: canonicalGoal(g), version: ev(g), updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt || null }),
    fromEntity: e => e.payload, itemLabel: g => g?.title ?? '',
});

const _journalsAdapter = createSyncAdapter({
    type: 'journals', endpoint: '/tplanner/journals', unitName: '篇',
    toEntity: _je, fromEntity: e => ({ date: e.id, ...e.payload }),
    localToEntities: obj => Object.entries(obj || {}).map(([d, e]) => _je([d, e])),
    remoteToEntities: obj => Object.entries(obj || {}).map(([d, e]) => _je([d, e])),
    entitiesToLocal: entities => { const r = {}; for (const e of entities) r[e.id] = { ...e.payload }; return r; },
    itemLabel: item => { const t = (item?.text || '').replace(/\s+/g, ' ').trim().slice(0, 20); return t ? `${item.date} · ${t}${item.text?.length > 20 ? '…' : ''}` : item?.date ?? ''; },
});

export const BUILTIN_ADAPTERS = { events: _eventsAdapter, goals: _goalsAdapter, journals: _journalsAdapter };
