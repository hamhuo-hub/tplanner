// Insights SyncAdapter — 认知洞察 / 焦虑追踪数据源
// 对应移动端 InsightStore 的两类数据：
//   StructuredEntry[] — 单个焦虑事件的结构化分析
//   DayReport{date}   — 每日 LLM 生成的日终报告
// 服务端统一存储为 { entries: [...], reports: {...} }。
// 桌面端目前没有 InsightStore，此 adapter 以 pass-through 模式运行。
//
// 通过 localToEntities / remoteToEntities / entitiesToLocal 三个转换器接入
// createSyncAdapter，分析与合并（含 base 快照三方对比、人工裁决）全部继承
// 工厂实现，不再各写一份。
import { createSyncAdapter } from '../utils/syncLogic';

// ── 规范形（必须与 sync-server/server.js 中同名函数逐字一致）──────────────
function canonicalStructuredEntry(e) {
    const c = {
        ...e,
        text: e.text || '', location: e.location || '',
        lat: e.lat ?? 0, lng: e.lng ?? 0, intensity: e.intensity ?? 0,
        distortions: e.distortions ?? [], autoThought: e.autoThought || '',
        thoughtConfidence: e.thoughtConfidence ?? 0, rationalResponse: e.rationalResponse || '',
        emotion: e.emotion || '', updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? null,
    };
    delete c.version;
    return c;
}

function canonicalDayReport(r) {
    const c = {
        ...r,
        totalEvents: r.totalEvents ?? 0, avgIntensity: r.avgIntensity ?? 0,
        distortionCounts: r.distortionCounts ?? {}, topLocation: r.topLocation || '',
        topTimeSlot: r.topTimeSlot || '', narrative: r.narrative || '',
        updatedAt: r.updatedAt || 0, deletedAt: r.deletedAt ?? null,
    };
    delete c.version;
    return c;
}

const _toEntryEntity = (e) => ({
    id: e.id, payload: canonicalStructuredEntry(e),
    updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? null,
});

const _toReportEntity = (date, r) => ({
    id: date, payload: canonicalDayReport(r),
    updatedAt: r.updatedAt || 0, deletedAt: r.deletedAt ?? null,
});

// { entries, reports } → 统一实体数组（entry 用 UUID、report 用日期作 id，不冲突）
function toEntities(data) {
    const d = data || {};
    return [
        ...(d.entries || []).map(_toEntryEntity),
        ...Object.entries(d.reports || {}).map(([date, r]) => _toReportEntity(date, r)),
    ];
}

export function createInsightsAdapter(opts = {}) {
    const { getLocal, writeLocal } = opts;

    const adapter = createSyncAdapter({
        type: 'insights', endpoint: '/tplanner/insights', unitName: '条',

        toEntity(_) { return { id: '', payload: {}, updatedAt: 0, deletedAt: null }; },
        fromEntity(e) { return e.payload; },

        localToEntities: toEntities,
        remoteToEntities: toEntities,

        entitiesToLocal(entities) {
            const entries = [], reports = {};
            for (const e of entities) {
                if (e.payload && typeof e.payload.narrative !== 'undefined') {
                    reports[e.id] = { date: e.id, ...e.payload };
                } else {
                    entries.push({ id: e.id, ...e.payload });
                }
            }
            return { entries, reports };
        },

        itemLabel(item) {
            if (item?.narrative) return `报告 · ${item.date ?? ''}`;
            const text = (item?.text || '').replace(/\s+/g, ' ').trim().slice(0, 25);
            return text || (item?.id ? `记录 · ${item.id.slice(0, 8)}` : '');
        },
    });

    if (getLocal)   adapter._getLocal   = getLocal;
    if (writeLocal) adapter._writeLocal = writeLocal;
    return adapter;
}
