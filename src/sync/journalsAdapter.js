// Journals SyncAdapter — 每日随笔数据源
// 本地存储为 { [date]: { text, updatedAt, deletedAt } } 的对象。
import { createSyncAdapter } from '../utils/syncLogic';

export function createJournalsAdapter(getLocal, writeLocal) {
    const _toEntry = (date, entry) => ({
        id: date, payload: entry || {}, updatedAt: entry?.updatedAt || 0, deletedAt: entry?.deletedAt ?? null,
    });

    return createSyncAdapter({
        type: 'journals', endpoint: '/tplanner/journals', unitName: '篇',

        toEntity([date, entry]) { return _toEntry(date, entry); },
        fromEntity(e) { return { date: e.id, ...e.payload }; },

        localToEntities(obj) {
            if (!obj || typeof obj !== 'object') return [];
            return Object.entries(obj).map(([d, e]) => _toEntry(d, e));
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
}
