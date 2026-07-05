// Events SyncAdapter — 日历事件数据源
// 事件是核心数据（isRequired: true），同步失败会阻塞整个流程。
import { createSyncAdapter, canonicalEvent } from '../utils/syncLogic';

export function createEventsAdapter(getLocal, writeLocal) {
    return createSyncAdapter({
        type: 'events', endpoint: '/tplanner/events', isRequired: true, unitName: '条',

        toEntity(e) {
            return { id: e.id, payload: canonicalEvent(e), updatedAt: e.updatedAt || 0, deletedAt: e.deletedAt || null };
        },
        fromEntity(e) { return e.payload; },

        localToEntities(local) {
            return (Array.isArray(local) ? local : []).map(this.toEntity);
        },
        entitiesToLocal(entities) {
            return entities.map(this.fromEntity);
        },
        itemLabel(e) { return e?.title ?? ''; },
    });
}
