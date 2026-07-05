// Insights SyncAdapter — 认知洞察 / 焦虑追踪数据源
// 对应移动端 InsightStore 的两类数据：
//   StructuredEntry[] — 单个焦虑事件的结构化分析
//   DayReport{date}   — 每日 LLM 生成的日终报告
// 服务端统一存储为 { entries: [...], reports: {...} }。
// 桌面端目前没有 InsightStore，此 adapter 以 pass-through 模式运行。
import { createSyncAdapter, analyzeEntities, mergeEntities, convertResults } from '../utils/syncLogic';

// ── 规范形（必须与 sync-server/server.js 中同名函数逐字一致）──────────────
function canonicalStructuredEntry(e) {
    return {
        ...e,
        text: e.text || '', location: e.location || '',
        lat: e.lat ?? 0, lng: e.lng ?? 0, intensity: e.intensity ?? 0,
        distortions: e.distortions ?? [], autoThought: e.autoThought || '',
        thoughtConfidence: e.thoughtConfidence ?? 0, rationalResponse: e.rationalResponse || '',
        emotion: e.emotion || '', updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? null,
    };
}

function canonicalDayReport(r) {
    return {
        ...r,
        totalEvents: r.totalEvents ?? 0, avgIntensity: r.avgIntensity ?? 0,
        distortionCounts: r.distortionCounts ?? {}, topLocation: r.topLocation || '',
        topTimeSlot: r.topTimeSlot || '', narrative: r.narrative || '',
        updatedAt: r.updatedAt || 0, deletedAt: r.deletedAt ?? null,
    };
}

const _toEntryEntity = (e) => ({
    id: e.id, payload: canonicalStructuredEntry(e),
    updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt ?? null,
});

const _toReportEntity = (date, r) => ({
    id: date, payload: canonicalDayReport(r),
    updatedAt: r.updatedAt || 0, deletedAt: r.deletedAt ?? null,
});

// 从远端 { entries, reports } JSON 提取统一实体数组
function remoteToEntities(remote) {
    const data = remote || {};
    return [
        ...(data.entries || []).map(_toEntryEntity),
        ...Object.entries(data.reports || {}).map(([d, r]) => _toReportEntity(d, r)),
    ];
}

export function createInsightsAdapter(opts = {}) {
    const { getLocal, writeLocal } = opts;

    return createSyncAdapter({
        type: 'insights', endpoint: '/tplanner/insights', unitName: '条',

        toEntity(_) { return { id: '', payload: {}, updatedAt: 0, deletedAt: null }; },
        fromEntity(e) { return e.payload; },

        localToEntities(local) {
            const data = local || { entries: [], reports: {} };
            return [
                ...(data.entries || []).map(_toEntryEntity),
                ...Object.entries(data.reports || {}).map(([d, r]) => _toReportEntity(d, r)),
            ];
        },

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

        // 覆写：远端 JSON 格式是 { entries, reports } 而非数组
        analyze(local, remote) {
            return convertResults(
                analyzeEntities(this.localToEntities(local), remoteToEntities(remote)),
                this.fromEntity);
        },

        merge(local, remote) {
            return this.entitiesToLocal(
                mergeEntities(this.localToEntities(local), remoteToEntities(remote)));
        },

        itemLabel(item) {
            if (item?.narrative) return `报告 · ${item.date ?? ''}`;
            const text = (item?.text || '').replace(/\s+/g, ' ').trim().slice(0, 25);
            return text || (item?.id ? `记录 · ${item.id.slice(0, 8)}` : '');
        },
    });
}
