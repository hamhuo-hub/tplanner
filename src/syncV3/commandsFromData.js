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

const hasOwn = (value, key) => Object.prototype.hasOwnProperty.call(value ?? {}, key);
const same = (left, right) => JSON.stringify(left) === JSON.stringify(right);
const KNOWN_EVENT_FIELDS = new Set([
    'id', 'title', 'type', 'itemType', 'start', 'end', 'note', 'completed',
    'checklist', 'colorId', 'recurrence', 'recurrenceType', 'recurrenceCount',
    'listId', 'alarmEnabled', 'alarmOffsetMinutes', 'lat', 'lng', 'latitude',
    'longitude', 'timezone', 'extras', 'lifecycle', 'version', 'updatedAt', 'deletedAt',
]);

function checklistOf(value) {
    const seen = new Set();
    return (Array.isArray(value) ? value : []).flatMap((item) => {
        const id = typeof item?.id === 'string' ? item.id : '';
        if (!id || seen.has(id)) return [];
        seen.add(id);
        return [{
            id,
            title: typeof item.title === 'string' ? item.title : String(item.text ?? ''),
            completed: Boolean(item.completed),
        }];
    });
}

function recurrenceOf(event) {
    if (hasOwn(event, 'recurrence')) return { present: true, value: event.recurrence ?? null };
    if (!hasOwn(event, 'recurrenceType') && !hasOwn(event, 'recurrenceCount')) {
        return { present: false, value: null };
    }
    const frequency = event.recurrenceType;
    if (!frequency || frequency === 'none') return { present: true, value: null };
    return {
        present: true,
        value: { frequency, count: Math.max(1, Number(event.recurrenceCount) || 1) },
    };
}

function extrasOf(event) {
    const extras = event?.extras && typeof event.extras === 'object' && !Array.isArray(event.extras)
        ? { ...event.extras }
        : {};
    let present = hasOwn(event, 'extras');
    for (const [key, value] of Object.entries(event ?? {})) {
        if (!KNOWN_EVENT_FIELDS.has(key)) {
            extras[key] = value;
            present = true;
        }
    }
    if (hasOwn(event, 'timezone')) {
        extras.timezone = event.timezone;
        present = true;
    }
    return { present, value: extras };
}

function appendChecklistCommands(commands, taskId, currentValue, localValue) {
    const current = checklistOf(currentValue);
    const local = checklistOf(localValue);
    const currentById = new Map(current.map((item) => [item.id, item]));
    const localIds = new Set(local.map((item) => item.id));

    for (const item of local) {
        const previous = currentById.get(item.id);
        if (!previous) {
            commands.push({
                type: 'checklist.createItem',
                aggregateId: taskId,
                arguments: { checklistItemId: item.id, title: item.title },
            });
            if (item.completed) {
                commands.push({
                    type: 'checklist.setCompleted',
                    aggregateId: taskId,
                    arguments: { checklistItemId: item.id, completed: true },
                });
            }
        } else {
            if (previous.title !== item.title) {
                commands.push({
                    type: 'checklist.setTitle',
                    aggregateId: taskId,
                    arguments: { checklistItemId: item.id, title: item.title },
                });
            }
            if (previous.completed !== item.completed) {
                commands.push({
                    type: 'checklist.setCompleted',
                    aggregateId: taskId,
                    arguments: { checklistItemId: item.id, completed: item.completed },
                });
            }
        }
    }
    for (const item of current) {
        if (!localIds.has(item.id)) {
            commands.push({
                type: 'checklist.deleteItem',
                aggregateId: taskId,
                arguments: { checklistItemId: item.id },
            });
        }
    }

    const order = current.filter((item) => localIds.has(item.id)).map((item) => item.id);
    for (const item of local) if (!order.includes(item.id)) order.push(item.id);
    const desired = local.map((item) => item.id);
    for (let index = 0; index < desired.length; index += 1) {
        if (order[index] === desired[index]) continue;
        const from = order.indexOf(desired[index]);
        if (from < 0) continue;
        order.splice(from, 1);
        const beforeItemId = order[index] ?? null;
        order.splice(index, 0, desired[index]);
        commands.push({
            type: 'checklist.reorderItem',
            aggregateId: taskId,
            arguments: { checklistItemId: desired[index], beforeItemId },
        });
    }
}

function appendTaskDetails(commands, id, current, event) {
    const note = typeof event.note === 'string' ? event.note : '';
    if (hasOwn(event, 'note') && (current.note ?? '') !== note) {
        commands.push({ type: 'task.setNote', aggregateId: id, arguments: { note } });
    }

    const completed = Boolean(event.completed);
    if (hasOwn(event, 'completed') && Boolean(current.completed) !== completed) {
        commands.push({ type: 'task.setCompleted', aggregateId: id, arguments: { completed } });
    }

    if (hasOwn(event, 'start') || hasOwn(event, 'end')) {
        const schedule = scheduleOf(event);
        if (!same(current.schedule ?? null, schedule)) {
            commands.push({ type: 'task.setSchedule', aggregateId: id, arguments: { schedule } });
        }
    }

    const recurrence = recurrenceOf(event);
    if (recurrence.present && !same(current.recurrence ?? null, recurrence.value)) {
        commands.push({
            type: 'task.setRecurrence', aggregateId: id, arguments: { recurrence: recurrence.value },
        });
    }

    if (hasOwn(event, 'listId')) {
        const listId = event.listId || null;
        if ((current.listId ?? null) !== listId) {
            commands.push({ type: 'task.assignList', aggregateId: id, arguments: { listId } });
        }
    }

    if (hasOwn(event, 'colorId')) {
        const colorId = Number(event.colorId) || 0;
        if ((current.colorId ?? 0) !== colorId) {
            commands.push({ type: 'task.setAppearance', aggregateId: id, arguments: { colorId } });
        }
    }

    if (hasOwn(event, 'alarmEnabled') || hasOwn(event, 'alarmOffsetMinutes')) {
        const alarm = {
            enabled: Boolean(event.alarmEnabled),
            offsetMinutes: Number(event.alarmOffsetMinutes) || 0,
        };
        if (!same(current.alarm ?? { enabled: false, offsetMinutes: 0 }, alarm)) {
            commands.push({ type: 'task.setAlarm', aggregateId: id, arguments: alarm });
        }
    }

    if (['lat', 'lng', 'latitude', 'longitude'].some((key) => hasOwn(event, key))) {
        const location = {
            lat: event.lat ?? event.latitude ?? null,
            lng: event.lng ?? event.longitude ?? null,
        };
        if (!same(current.location ?? { lat: null, lng: null }, location)) {
            commands.push({ type: 'task.setLocation', aggregateId: id, arguments: location });
        }
    }

    const extras = extrasOf(event);
    if (extras.present && !same(current.extras ?? {}, extras.value)) {
        commands.push({ type: 'task.setExtras', aggregateId: id, arguments: { extras: extras.value } });
    }

    if (hasOwn(event, 'checklist')) appendChecklistCommands(commands, id, current.checklist, event.checklist);
}

export function diffEventsToCommands(mirror, localEvents) {
    const commands = [];
    const tasks = mirror?.tasks ?? {};
    for (const e of Array.isArray(localEvents) ? localEvents : []) {
        const id = e?.id;
        if (id == null || id === '') continue;
        const cur = tasks[id];

        if (!cur) {
            const itemType = e.itemType ?? e.type ?? 'task';
            commands.push({ type: 'task.create', aggregateId: id, arguments: {
                title: typeof e.title === 'string' ? e.title : '',
                itemType,
            }});
            appendTaskDetails(commands, id, {
                title: typeof e.title === 'string' ? e.title : '',
                note: '', completed: false, itemType,
            }, e);
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

        const itemType = typeof (e.itemType ?? e.type) === 'string' ? (e.itemType ?? e.type) : cur.itemType;
        if (itemType && cur.itemType !== itemType) {
            commands.push({ type: 'task.changeType', aggregateId: id, arguments: { itemType } });
        }
        appendTaskDetails(commands, id, cur, e);
    }
    return commands;
}

export function diffListsToCommands(mirror, localLists) {
    const commands = [];
    const lists = mirror?.customLists ?? {};
    for (const list of Array.isArray(localLists) ? localLists : []) {
        const id = list?.id;
        if (!id) continue;
        const current = lists[id];
        const title = String(list.title ?? list.name ?? '');
        const color = list.color ?? null;
        if (!current) {
            commands.push({ type: 'list.create', aggregateId: id, arguments: { title, color } });
            if (isDeleted(list)) commands.push({ type: 'list.delete', aggregateId: id, arguments: {} });
            continue;
        }
        if (isDeleted(list)) {
            if (current.lifecycle === 'active') commands.push({ type: 'list.delete', aggregateId: id, arguments: {} });
            continue;
        }
        if (current.lifecycle === 'deleted') continue;
        if (current.title !== title) commands.push({ type: 'list.rename', aggregateId: id, arguments: { title } });
        if ((current.color ?? null) !== color) commands.push({ type: 'list.setColor', aggregateId: id, arguments: { color } });
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
    return Object.entries(tasks).map(([id, t]) => {
        const recurrence = t.recurrence ?? null;
        return {
            ...(t.extras ?? {}),
            id,
            title: t.title ?? '',
            note: t.note ?? '',
            completed: t.completed ?? false,
            type: t.itemType ?? 'task',
            itemType: t.itemType ?? 'task',
            start: t.schedule?.startAt ? new Date(t.schedule.startAt) : null,
            end: t.schedule?.endAt ? new Date(t.schedule.endAt) : null,
            recurrence,
            recurrenceType: recurrence?.frequency ?? 'none',
            recurrenceCount: recurrence?.count ?? 1,
            listId: t.listId ?? null,
            colorId: t.colorId ?? 0,
            alarmEnabled: Boolean(t.alarm?.enabled),
            alarmOffsetMinutes: t.alarm?.offsetMinutes ?? 0,
            lat: t.location?.lat ?? null,
            lng: t.location?.lng ?? null,
            extras: t.extras ?? {},
            checklist: checklistOf(t.checklist).map((item) => ({
                id: item.id, text: item.title, completed: item.completed,
            })),
            deletedAt: t.lifecycle === 'deleted' ? new Date() : null,
            updatedAt: 0,
        };
    });
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
