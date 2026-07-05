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

// ── 稳定序列化（内容比较专用）────────────────────────────────────────────────
// JSON.stringify 的输出依赖对象键的插入顺序：同一条记录，本地（RxDB 读出）和
// 远端（安卓端 org.json 写出）的键序不同，字节串就不同——即使内容完全一致。
// 内容比较必须用键按字典序排序的稳定序列化，否则"相同内容"永远判不相等。
// 安卓端 LanSyncManager.tieKey 手工拼出与此逐字一致的字符串，改这里必须同步改那里。
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

// ── 事件/目标的规范形（canonical form）──────────────────────────────────────
// 同一条记录在不同端的表示会漂移：桌面端 Date.toISOString() 带毫秒而安卓端
// Instant.toString() 不带；安卓端不认识的可选字段会被丢掉；桌面端落盘时又会
// 补默认值。这些"表示差异"在字节比较下全是"内容不同"——updatedAt 相同、内容
// 也相同的记录被判为冲突，平局裁决永远选中对端版本，而本地落盘后又变回自己
// 的表示，于是同一批记录每次同步都显示"将被覆盖"，永不收敛。
// 因此进入比较/合并前先统一成规范形：时间统一为带毫秒的 ISO、可选字段统一补
// 默认值。合并输出也是规范形——推给服务器和写入本地的是同一份数据，服务器上
// 的旧格式副本会在下一次合并时被规范形自然替换（自愈）。
// 注意：这里的默认值必须与 App.jsx onMergeEvents/onMergeGoals 落盘时补的默认值
// 保持一致，否则"落盘的"和"比较的"又会漂移出新的差异。
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

// updatedAt 相同时（典型情况：旧版迁移记录、或两端在同一毫秒各自独立创建/编辑），
// 用与"谁是 local/remote"无关的确定性方式选出胜者，保证两端最终收敛一致。
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
            if (contentKey(le) === contentKey(re)) { results.synced.push(le); continue; }
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
// events/goals 本身已是 { id, updatedAt, deletedAt, ... } 的数组，规范形整条记录
// 即 payload；journals 以日期为键、条目为 { text, updatedAt, deletedAt }，日期本身
// 就是 id。fromEntity 对 events/goals 直接还原（规范形）对象；journalFromEntity
// 额外把 id 还原成 date 字段。
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
        setClockOffset((peerNow + rtt / 2) - t1);
    } catch (_) { /* clock sync is best-effort; fall back to local clock (offset 0) */ }
}

// ── SyncAdapter — 可组合的同步数据源抽象 ─────────────────────────────────────
// 每种需要同步的数据类型只需提供一个 adapter（实现 toEntity / fromEntity /
// localToEntities / entitiesToLocal），sync 引擎不关心数据形状。
// 核心的 compare / merge / analyze 全部复用已有的统一实体引擎。
//
// 旧函数 analyzeConflict / mergeEvents 等仍导出以维持向后兼容，
// 新代码应直接使用 createSyncAdapter + fetchAndAnalyze + syncAndPush。

/** 把 analyzeEntities 的结果通过 toDisplay 还原为本地展示形状 */
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

/**
 * createSyncAdapter(config) — 构建一个同步数据源描述符。
 *
 * @param {Object} config
 * @param {string} config.type         - 类型名，如 'events' / 'goals' / 'journals' / 'insights'
 * @param {string} config.endpoint     - 服务端路径，如 '/tplanner/events'
 * @param {boolean}[config.isRequired] - 核心数据（失败阻断），默认 false
 * @param {string}[config.unitName]    - UI 量词，如 '条' / '篇' / '个'
 * @param {(item)=>Entity} config.toEntity       - 单条 → 统一实体
 * @param {(entity)=>item} config.fromEntity     - 统一实体 → 单条
 * @param {(local)=>Entity[]}[config.localToEntities]  - 本地数据 → 统一实体数组
 * @param {(Entity[])=>local}[config.entitiesToLocal]  - 统一实体数组 → 本地数据
 * @param {(item)=>string}[config.itemLabel]     - 冲突预览中的单条描述
 */
export function createSyncAdapter(config) {
    const { type, endpoint, isRequired = false, unitName = '条',
            toEntity, fromEntity, localToEntities, entitiesToLocal, itemLabel } = config;

    const _localToEntities = localToEntities || (local =>
        (Array.isArray(local) ? local : []).map(toEntity));
    const _entitiesToLocal = entitiesToLocal || (entities =>
        entities.map(fromEntity));

    return {
        type, endpoint, isRequired, unitName,
        toEntity, fromEntity,
        localToEntities: _localToEntities,
        entitiesToLocal: _entitiesToLocal,
        itemLabel: itemLabel || (item => item?.title ?? item?.text ?? ''),

        /** 冲突分析：返回 { added, removed, updated, deleted, synced, conflicted } */
        analyze(local, remote) {
            const locs = _localToEntities(local);
            const rems = (Array.isArray(remote) ? remote : []).map(toEntity);
            return convertResults(analyzeEntities(locs, rems), fromEntity);
        },

        /** 合并：返回合并后的本地格式数据 */
        merge(local, remote) {
            const locs = _localToEntities(local);
            const rems = (Array.isArray(remote) ? remote : []).map(toEntity);
            return _entitiesToLocal(mergeEntities(locs, rems));
        },
    };
}

/**
 * 对单个 adapter 执行同步预览：拉取远端 + 冲突分析（不合并，不写本地）。
 * @returns {{ adapter, remoteData, analysis } | null}
 */
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

/**
 * 对单个 adapter 执行完整同步合并：拉取 → 合并 → 推送 → 返回本地格式数据。
 * @returns {any | null} 合并后的本地格式数据；失败返回 null
 */
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

// ── 内联 fallback adapters（供旧调用路径 resolveAdapters 使用）──────────────
// 新代码应从 src/sync/*Adapter.js import 并在 App.jsx 中组装 adapters[]。

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
