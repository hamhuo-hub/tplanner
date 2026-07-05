// Goals SyncAdapter — 十年计划 / 六边形网格目标数据源
import { createSyncAdapter, canonicalGoal } from '../utils/syncLogic';

export function createGoalsAdapter(getLocal, writeLocal) {
    return createSyncAdapter({
        type: 'goals', endpoint: '/tplanner/goals', unitName: '个',

        toEntity(g) {
            return { id: g.id, payload: canonicalGoal(g), updatedAt: g.updatedAt || 0, deletedAt: g.deletedAt || null };
        },
        fromEntity(e) { return e.payload; },

        localToEntities(local) {
            return (Array.isArray(local) ? local : []).map(this.toEntity);
        },
        entitiesToLocal(entities) {
            return entities.map(this.fromEntity);
        },
        itemLabel(g) { return g?.title ?? ''; },
    });
}
