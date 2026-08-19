// 本地数据 ↔ V3 命令的 diff 与投影(纯函数,见 docs/sync-v3.md §16)。
//
// 客户端不再合并:把"本地现状 vs 中央镜像"的差异转成语义命令,
// 权威裁决完全交给中央 reducer。桌面事件用 start/end 表达排程,
// V3 实体用 schedule { startAt, endAt },这里做形状换算。
import { emptyState } from './localReducer';

const isDeleted = (e) => Boolean(e?.deletedAt);

function scheduleOf(event) {
    if (!event?.start) return null;
    return {
        startAt: new Date(event.start).toISOString(),
        endAt: event.end ? new Date(event.end).toISOString() : null,
    };
}

export function diffEventsToCommands(mirror, localEvents) {
    const commands = [];
    const tasks = mirror?.tasks ?? {};
    for (const e of Array.isArray(localEvents) ? localEvents : []) {
        const id = e?.id;
        if (id == null || id === '') continue;
        const cur = tasks[id];

        if (!cur) {
            commands.push({ type: 'task.create', aggregateId: id, arguments: {
                title: typeof e.title === 'string' ? e.title : '',
                itemType: e.itemType ?? 'task',
            }});
            if (typeof e.note === 'string' && e.note !== '') {
                commands.push({ type: 'task.setNote', aggregateId: id, arguments: { note: e.note } });
            }
            if (e.completed === true) {
                commands.push({ type: 'task.setCompleted', aggregateId: id, arguments: { completed: true } });
            }
            const schedule = scheduleOf(e);
            if (schedule) {
                commands.push({ type: 'task.setSchedule', aggregateId: id, arguments: { schedule } });
            }
            if (isDeleted(e)) commands.push({ type: 'task.delete', aggregateId: id, arguments: {} });
            continue;
        }

        if (isDeleted(e) && cur.lifecycle === 'active') {
            commands.push({ type: 'task.delete', aggregateId: id, arguments: {} });
        }
        if (!isDeleted(e) && cur.lifecycle === 'deleted') {
            commands.push({ type: 'task.restore', aggregateId: id, arguments: {} });
        }
        if (isDeleted(e)) continue;

        const title = typeof e.title === 'string' ? e.title : '';
        if (cur.title !== title) commands.push({ type: 'task.setTitle', aggregateId: id, arguments: { title } });

        const note = typeof e.note === 'string' ? e.note : '';
        if ((cur.note ?? '') !== note) commands.push({ type: 'task.setNote', aggregateId: id, arguments: { note } });

        const completed = Boolean(e.completed);
        if (Boolean(cur.completed) !== completed) {
            commands.push({ type: 'task.setCompleted', aggregateId: id, arguments: { completed } });
        }

        const itemType = typeof e.itemType === 'string' ? e.itemType : cur.itemType;
        if (itemType && cur.itemType !== itemType) {
            commands.push({ type: 'task.changeType', aggregateId: id, arguments: { itemType } });
        }

        const schedule = scheduleOf(e);
        // 事件无排程且镜像也无排程 → 无差异;仅在任一侧有排程时才比较
        const scheduleRelevant = schedule !== null || cur.schedule !== undefined;
        if (scheduleRelevant && JSON.stringify(cur.schedule) !== JSON.stringify(schedule)) {
            commands.push({ type: 'task.setSchedule', aggregateId: id, arguments: { schedule } });
        }
    }
    return commands;
}

export function diffJournalsToCommands(mirror, localJournals) {
    const commands = [];
    const journals = mirror?.journals ?? {};
    for (const [date, entry] of Object.entries(localJournals ?? {})) {
        const value = typeof entry === 'string' ? { text: entry } : entry;
        const cur = journals[date];
        const text = value?.text ?? '';
        if (value?.deletedAt) {
            if (cur && cur.lifecycle === 'active') {
                commands.push({ type: 'journal.delete', aggregateId: date, arguments: {} });
            }
            continue;
        }
        if (!cur) {
            if (text !== '') commands.push({ type: 'journal.setText', aggregateId: date, arguments: { text } });
        } else if (cur.lifecycle === 'active' && cur.text !== text) {
            commands.push({ type: 'journal.setText', aggregateId: date, arguments: { text } });
        }
    }
    return commands;
}

// ── 投影:V3 展示状态 → 桌面/Web UI 熟悉的形状(本地 UI 缓存,非权威)─────────

export function toLegacyEvents(state) {
    const tasks = state?.tasks ?? {};
    return Object.entries(tasks).map(([id, t]) => ({
        id,
        title: t.title ?? '',
        note: t.note ?? '',
        completed: t.completed ?? false,
        itemType: t.itemType ?? 'task',
        start: t.schedule?.startAt ? new Date(t.schedule.startAt) : null,
        end: t.schedule?.endAt ? new Date(t.schedule.endAt) : null,
        recurrence: t.recurrence ?? null,
        listId: t.listId ?? null,
        checklist: t.checklist ?? [],
        deletedAt: t.lifecycle === 'deleted' ? new Date() : null,
        updatedAt: 0,
    }));
}

export function toLegacyJournals(state) {
    const journals = state?.journals ?? {};
    const out = {};
    for (const [date, j] of Object.entries(journals)) {
        out[date] = {
            text: j.text ?? '',
            updatedAt: 0,
            deletedAt: j.lifecycle === 'deleted' ? new Date() : null,
        };
    }
    return out;
}

export { emptyState };
