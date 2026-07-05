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
// 比较 / 合并 / 冲突分析核心。

export function isAlive(e) { return !e.deletedAt; }

// ── 稳定序列化（内容比较专用）────────────────────────────────────────────────
// JSON.stringify 的输出依赖对象键的插入顺序：同一条记录，本地（RxDB 读出）和
// 远端（安卓端 org.json 写出）的键序不同，字节串就不同——即使内容完全一致。
// 内容比较必须用键按字典序排序的稳定序列化，否则"相同内容"永远判不相等。
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

// ── 规范形（canonical form）──────────────────────────────────────────────────
const toIsoMs = (v) => {
    const t = new Date(v).getTime();
    return Number.isFinite(t) ? new Date(t).toISOString() : v;
};

export function canonicalEvent(e) {
    return {
        ...e,
        type:            e.type || 'event',
        start:           toIsoMs(e.start),
        end:             toIsoMs(e.end),
        note:            e.note || '',
        timezone:        e.timezone || '',
        groupId:         e.groupId || '',
        colorId:         e.colorId ?? 0,
        completed:       e.completed ?? false,
        checklist:       e.checklist ?? [],
        recurrenceType:  e.recurrenceType || 'none',
        recurrenceCount: e.recurrenceCount || 1,
        updatedAt:       e.updatedAt || 0,
        deletedAt:       e.deletedAt ?? 0,
    };
}

export function canonicalGoal(g) {
    return {
        ...g,
        note:      g.note ?? '',
        icon:      g.icon ?? '',
        order:     g.order ?? 0,
        updatedAt: g.updatedAt || 0,
        deletedAt: g.deletedAt ?? 0,
    };
}

// 内容比较键：payload 应已是规范形，稳定序列化后即可字节比较。
function contentKey(e) {
    return stableStringify({ payload: e?.payload, deletedAt: e?.deletedAt ?? null });
}

// ── 纯内容裁决 —— 不依赖壁钟（updatedAt）─────────────────────────────────────
// 内容相同即同一版本；内容不同时用确定性 content-key 打破平局，
// 保证所有端独立得出相同结论。必须与 sync-server/server.js 中的同名实现逐字一致。
export function pickEntity(a, b) {
    if (contentKey(a) === contentKey(b)) return a;
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

// ── 冲突分析（content-first + tombstone 感知）────────────────────────────────
// 内容相同 → synced。内容不同时：
//   - 一端已删除 → deleted（tombstone 自动传播，删除意图明确）
//   - 两端都存活 → conflicted（用户手动裁决，不做时间戳自动覆盖）
export function analyzeEntities(local, remote) {
    const localMap  = new Map(local.map(e  => [e.id, e]));
    const remoteMap = new Map(remote.map(e => [e.id, e]));
    const results = { added: [], removed: [], updated: [], deleted: [], synced: [], conflicted: [] };

    for (const [id, re] of remoteMap) {
        const le = localMap.get(id);
        if (!le) {
            if (isAlive(re)) results.added.push(re);
            continue;
        }
        // 内容相同 → 已同步（不依赖 updatedAt）
        if (contentKey(le) === contentKey(re)) { results.synced.push(le); continue; }
        // 内容不同 → tombstone 自动传播，其余交用户裁决
        if (!isAlive(re) && isAlive(le)) {
            results.deleted.push({ local: le, remote: re });
        } else if (!isAlive(le) && isAlive(re)) {
            results.deleted.push({ local: le, remote: re });
        } else {
            results.conflicted.push({ local: le, remote: re });
        }
    }
    for (const [id, le] of localMap) {
        if (!remoteMap.has(id) && isAlive(le)) results.removed.push(le);
    }
    return results;
}

// ── 各数据类型 ↔ 统一实体 的适配层 ───────────────────────────────────────────
const toEventEntity   = e => ({ id: e.id, payload: canonicalEvent(e), updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt || null });
const toGoalEntity    = g => ({ id: g.id, payload: canonicalGoal(g),  updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt || null });
const toJournalEntity = (date, entry) => ({ id: date, payload: entry || {}, updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null });
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

// ── 合并算法（纯内容裁决）────────────────────────────────────────────────────
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
    } catch (_) { /* clock sync is best-effort */ }
}

// ── SyncAdapter — 可组合的同步数据源抽象 ─────────────────────────────────────

export function convertResults(results, toDisplay) {
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

export function createSyncAdapter(config) {
    const { type, endpoint, isRequired = false, unitName = '条',
            toEntity, fromEntity, localToEntities, entitiesToLocal, itemLabel,
            remoteToEntities } = config;

    const _localToEntities = localToEntities || (local =>
        (Array.isArray(local) ? local : []).map(toEntity));
    const _entitiesToLocal = entitiesToLocal || (entities =>
        entities.map(fromEntity));
    const _remoteToEntities = remoteToEntities || (remote =>
        (Array.isArray(remote) ? remote : []).map(toEntity));

    return {
        type, endpoint, isRequired, unitName,
        toEntity, fromEntity,
        localToEntities: _localToEntities,
        entitiesToLocal: _entitiesToLocal,
        itemLabel: itemLabel || (item => item?.title ?? item?.text ?? ''),

        analyze(local, remote) {
            const locs = _localToEntities(local);
            const rems = _remoteToEntities(remote);
            return convertResults(analyzeEntities(locs, rems), fromEntity);
        },

        merge(local, remote) {
            const locs = _localToEntities(local);
            const rems = _remoteToEntities(remote);
            return _entitiesToLocal(mergeEntities(locs, rems));
        },
    };
}

export async function fetchAndAnalyze(adapter, serverUrl, localData) {
    try {
        const res = await fetch(`${serverUrl}${adapter.endpoint}`, {
            method: 'GET', signal: AbortSignal.timeout(10000),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const remoteData = await res.json();
        const analysis = adapter.analyze(localData, remoteData);
        return { adapter, remoteData, analysis };
    } catch (_) { return null; }
}

export async function syncAndPush(adapter, serverUrl, localData) {
    const base = normalizeServerUrl(serverUrl);
    try {
        const res = await fetch(`${base}${adapter.endpoint}`, {
            method: 'GET', signal: AbortSignal.timeout(10000),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const remoteData = await res.json();
        const merged = adapter.merge(localData, remoteData);
        await fetch(`${base}${adapter.endpoint}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(merged),
            signal: AbortSignal.timeout(10000),
        });
        return merged;
    } catch (_) { return null; }
}

// ── 内联 fallback adapters ──────────────────────────────────────────────────

const _eventsAdapter = createSyncAdapter({
    type: 'events', endpoint: '/tplanner/events', isRequired: true, unitName: '条',
    toEntity:   e => ({ id: e.id, payload: canonicalEvent(e), updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt || null }),
    fromEntity: e => e.payload,
    itemLabel:  e => e?.title ?? '',
});

const _goalsAdapter = createSyncAdapter({
    type: 'goals', endpoint: '/tplanner/goals', unitName: '个',
    toEntity:   g => ({ id: g.id, payload: canonicalGoal(g), updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt || null }),
    fromEntity: e => e.payload,
    itemLabel:  g => g?.title ?? '',
});

const _journalsAdapter = createSyncAdapter({
    type: 'journals', endpoint: '/tplanner/journals', unitName: '篇',
    toEntity:   ([date, entry]) => ({ id: date, payload: entry || {}, updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null }),
    fromEntity: e => ({ date: e.id, ...e.payload }),
    localToEntities(obj) {
        return Object.entries(obj || {}).map(([date, entry]) =>
            ({ id: date, payload: entry || {}, updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null }));
    },
    remoteToEntities(obj) {
        return Object.entries(obj || {}).map(([date, entry]) =>
            ({ id: date, payload: entry || {}, updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null }));
    },
    entitiesToLocal(entities) {
        const result = {};
        for (const e of entities) result[e.id] = { ...e.payload };
        return result;
    },
    itemLabel(item) {
        const text = (item?.text || '').replace(/\s+/g, ' ').trim().slice(0, 20);
        return text ? `${item.date} · ${text}${(item.text?.length > 20) ? '…' : ''}` : item?.date ?? '';
    },
});

export const BUILTIN_ADAPTERS = {
    events:   _eventsAdapter,
    goals:    _goalsAdapter,
    journals: _journalsAdapter,
};
