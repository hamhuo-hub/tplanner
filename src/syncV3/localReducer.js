// 本地乐观 reducer —— 与服务器 src/materializer/reducer.js 逐语义一致。
//
// 用途:Displayed State = reduce(Server Mirror, Pending Overlay) 的临时预览;
// 权威永远来自中央快照(见 docs/sync-v3.md §8/§9)。
// 与服务器版同一套铁律:纯函数、不读时钟、时间来自命令流、删除是生命周期、
// 重复同值 NOOP。契约测试用同一份 sequence-01 fixture 验证两实现产出相同状态。

export function emptyState() {
    return { tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {} };
}

const REJECTED = (errorCode) => ({ status: 'REJECTED', errorCode });
const NOOP = (errorCode) => ({ status: 'NOOP', ...(errorCode ? { errorCode } : {}) });
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value);
const jsonEqual = (left, right) => JSON.stringify(left) === JSON.stringify(right);

function createCanonicalTaskDefaults() {
    return {
        title: '',
        note: '',
        completed: false,
        itemType: 'task',
        schedule: null,
        recurrence: null,
        alarm: { enabled: false, offsetMinutes: 0 },
        colorId: 0,
        location: { lat: null, lng: null },
        extras: {},
        listId: null,
        checklist: [],
    };
}

function canonicalizeChecklistItem(item) {
    if (!isObject(item)) return item;
    if (!Object.hasOwn(item, 'text') && typeof item.title === 'string') return item;
    const { text, ...rest } = item;
    return {
        ...rest,
        title: typeof item.title === 'string' ? item.title : (typeof text === 'string' ? text : ''),
    };
}

function findActive(entity) {
    if (!entity) return { receipt: REJECTED('ENTITY_NOT_FOUND') };
    if (entity.lifecycle === 'deleted') return { receipt: { status: 'ENTITY_DELETED', errorCode: 'ENTITY_DELETED' } };
    return { entity };
}

function updateTask(state, id, updater) {
    const { entity, receipt } = findActive(state.tasks[id]);
    if (receipt) return { state, receipt };
    const next = updater(entity);
    if (next === entity) return { state, receipt: NOOP() };
    return { state: { ...state, tasks: { ...state.tasks, [id]: next } }, receipt: { status: 'APPLIED' } };
}

function setField(state, id, patch) {
    const { entity, receipt } = findActive(state.tasks[id]);
    if (receipt) return { state, receipt };
    return { state: { ...state, tasks: { ...state.tasks, [id]: { ...entity, ...patch } } }, receipt: { status: 'APPLIED' } };
}

const TASK = {
    'task.create'(state, cmd) {
        const id = cmd.aggregateId;
        if (!id) return { state, receipt: REJECTED('MISSING_AGGREGATE_ID') };
        if (state.tasks[id]) return { state, receipt: { status: 'ID_ALREADY_EXISTS', errorCode: 'ID_ALREADY_EXISTS' } };
        const args = cmd.arguments ?? {};
        const tasks = {
            ...state.tasks,
            [id]: {
                ...createCanonicalTaskDefaults(),
                title: typeof args.title === 'string' ? args.title : '',
                itemType: typeof args.itemType === 'string' ? args.itemType : 'task',
                lifecycle: 'active',
                deletedAt: null,
            },
        };
        return { state: { ...state, tasks }, receipt: { status: 'APPLIED' } };
    },

    'task.setTitle'(state, cmd) {
        const title = String(cmd.arguments?.title ?? '');
        return updateTask(state, cmd.aggregateId, (t) => (t.title === title ? t : { ...t, title }));
    },

    'task.setNote'(state, cmd) {
        const note = String(cmd.arguments?.note ?? '');
        return updateTask(state, cmd.aggregateId, (t) => (t.note === note ? t : { ...t, note }));
    },

    'task.setCompleted'(state, cmd) {
        const completed = Boolean(cmd.arguments?.completed);
        return updateTask(state, cmd.aggregateId, (t) => (t.completed === completed ? t : { ...t, completed }));
    },

    'task.setSchedule'(state, cmd) {
        const schedule = cmd.arguments?.schedule ?? null;
        if (schedule !== null && (
            !isObject(schedule)
            || !Object.hasOwn(schedule, 'startAt')
            || !Object.hasOwn(schedule, 'endAt')
            || ![schedule.startAt, schedule.endAt].every((value) => value === null || typeof value === 'string')
        )) {
            return { state, receipt: REJECTED('INVALID_SCHEDULE') };
        }
        return updateTask(state, cmd.aggregateId, (t) => {
            if (cmd.arguments?.ifMissing === true && t.schedule !== null) return t;
            return JSON.stringify(t.schedule) === JSON.stringify(schedule) ? t : { ...t, schedule };
        });
    },

    'task.setRecurrence'(state, cmd) {
        const recurrence = cmd.arguments?.recurrence ?? null;
        if (recurrence !== null && (
            !isObject(recurrence)
            || typeof recurrence.frequency !== 'string'
            || recurrence.frequency === ''
            || !Number.isInteger(recurrence.count)
            || recurrence.count < 1
        )) {
            return { state, receipt: REJECTED('INVALID_RECURRENCE') };
        }
        return updateTask(state, cmd.aggregateId, (t) => {
            if (cmd.arguments?.ifMissing === true && t.recurrence !== null) return t;
            return jsonEqual(t.recurrence, recurrence) ? t : { ...t, recurrence };
        });
    },

    'task.setAppearance'(state, cmd) {
        const colorId = Number(cmd.arguments?.colorId);
        if (!Number.isInteger(colorId) || colorId < 0) {
            return { state, receipt: REJECTED('INVALID_COLOR_ID') };
        }
        return updateTask(state, cmd.aggregateId, (t) => (t.colorId === colorId ? t : { ...t, colorId }));
    },

    'task.setAlarm'(state, cmd) {
        const enabled = cmd.arguments?.enabled;
        const offsetMinutes = Number(cmd.arguments?.offsetMinutes);
        if (typeof enabled !== 'boolean' || !Number.isInteger(offsetMinutes)) {
            return { state, receipt: REJECTED('INVALID_ALARM') };
        }
        const { ifMissing, ...alarmArguments } = cmd.arguments ?? {};
        return updateTask(state, cmd.aggregateId, (t) => {
            const current = t.alarm ?? {};
            if (ifMissing === true && (
                current.enabled !== false || current.offsetMinutes !== 0 || Object.keys(current).length > 2
            )) return t;
            const alarm = { ...current, ...alarmArguments, enabled, offsetMinutes };
            return JSON.stringify(t.alarm) === JSON.stringify(alarm) ? t : { ...t, alarm };
        });
    },

    'task.setLocation'(state, cmd) {
        const lat = cmd.arguments?.lat ?? null;
        const lng = cmd.arguments?.lng ?? null;
        const valid = (value) => value === null || (typeof value === 'number' && Number.isFinite(value));
        if (!valid(lat) || !valid(lng)) return { state, receipt: REJECTED('INVALID_LOCATION') };
        const { ifMissing, ...locationArguments } = cmd.arguments ?? {};
        return updateTask(state, cmd.aggregateId, (t) => {
            const current = t.location ?? {};
            if (ifMissing === true && (
                current.lat !== null || current.lng !== null || Object.keys(current).length > 2
            )) return t;
            const location = { ...current, ...locationArguments, lat, lng };
            return JSON.stringify(t.location) === JSON.stringify(location) ? t : { ...t, location };
        });
    },

    'task.setExtras'(state, cmd) {
        const extras = cmd.arguments?.extras;
        if (!extras || typeof extras !== 'object' || Array.isArray(extras)) {
            return { state, receipt: REJECTED('INVALID_EXTRAS') };
        }
        return updateTask(state, cmd.aggregateId, (t) => {
            const next = cmd.arguments?.mergeMissing === true
                ? Object.fromEntries([...Object.entries(extras), ...Object.entries(t.extras ?? {})])
                : { ...extras };
            return JSON.stringify(t.extras ?? {}) === JSON.stringify(next) ? t : { ...t, extras: next };
        });
    },

    'task.changeType'(state, cmd) {
        const itemType = cmd.arguments?.itemType;
        if (typeof itemType !== 'string') return { state, receipt: REJECTED('MISSING_ITEM_TYPE') };
        return updateTask(state, cmd.aggregateId, (t) => (t.itemType === itemType ? t : { ...t, itemType }));
    },

    'task.assignList'(state, cmd) {
        const listId = cmd.arguments?.listId ?? null;
        if (listId !== null) {
            const list = state.customLists[listId];
            if (!list || list.lifecycle === 'deleted') return { state, receipt: REJECTED('LIST_NOT_FOUND') };
        }
        return updateTask(state, cmd.aggregateId, (t) => {
            if (cmd.arguments?.ifUnassigned === true && t.listId !== null) return t;
            return t.listId === listId ? t : { ...t, listId };
        });
    },

    'task.moveInTimeline'(state, cmd) {
        const offsetMinutes = Number(cmd.arguments?.offsetMinutes);
        if (!Number.isFinite(offsetMinutes)) return { state, receipt: REJECTED('MISSING_OFFSET') };
        return updateTask(state, cmd.aggregateId, (t) => {
            if (!t.schedule?.startAt) return t;
            const shift = (iso) => new Date(new Date(iso).getTime() + offsetMinutes * 60_000).toISOString();
            return {
                ...t,
                schedule: { ...t.schedule, startAt: shift(t.schedule.startAt), endAt: t.schedule.endAt ? shift(t.schedule.endAt) : null },
            };
        });
    },

    'task.delete'(state, cmd, seq) {
        const t = state.tasks[cmd.aggregateId];
        if (!t) return { state, receipt: REJECTED('ENTITY_NOT_FOUND') };
        if (t.lifecycle === 'deleted') return { state, receipt: NOOP('NOOP_ALREADY_DELETED') };
        return {
            state: { ...state, tasks: { ...state.tasks, [cmd.aggregateId]: { ...t, lifecycle: 'deleted', deletedAt: seq } } },
            receipt: { status: 'APPLIED' },
        };
    },

    'task.restore'(state, cmd) {
        const t = state.tasks[cmd.aggregateId];
        if (!t) return { state, receipt: REJECTED('ENTITY_NOT_FOUND') };
        if (t.lifecycle === 'active') return { state, receipt: NOOP() };
        return {
            state: { ...state, tasks: { ...state.tasks, [cmd.aggregateId]: { ...t, lifecycle: 'active', deletedAt: null } } },
            receipt: { status: 'APPLIED' },
        };
    },
};

const CHECKLIST = {
    'checklist.createItem'(state, cmd) {
        const { entity, receipt } = findActive(state.tasks[cmd.aggregateId]);
        if (receipt) return { state, receipt };
        const args = cmd.arguments ?? {};
        const itemId = args.checklistItemId;
        if (typeof itemId !== 'string' || itemId === '') return { state, receipt: REJECTED('MISSING_CHECKLIST_ITEM_ID') };
        const checklist = entity.checklist ?? [];
        if (checklist.some((i) => i.id === itemId)) return { state, receipt: NOOP() };
        const item = {
            id: itemId,
            title: typeof args.title === 'string' ? args.title : (typeof args.text === 'string' ? args.text : ''),
            completed: false,
        };
        return setField(state, cmd.aggregateId, { checklist: [...checklist, item] });
    },

    'checklist.setTitle'(state, cmd) {
        const title = String(cmd.arguments?.title ?? cmd.arguments?.text ?? '');
        return updateChecklistItem(state, cmd, (item) => {
            const canonical = canonicalizeChecklistItem(item);
            return canonical.title === title ? canonical : { ...canonical, title };
        });
    },

    'checklist.setCompleted'(state, cmd) {
        const completed = Boolean(cmd.arguments?.completed);
        return updateChecklistItem(state, cmd, (item) => {
            const canonical = canonicalizeChecklistItem(item);
            return canonical.completed === completed ? canonical : { ...canonical, completed };
        });
    },

    'checklist.deleteItem'(state, cmd) {
        return updateChecklistItem(state, cmd, (item, checklist) => checklist.filter((i) => i.id !== item.id));
    },

    'checklist.reorderItem'(state, cmd) {
        const { entity, receipt } = findActive(state.tasks[cmd.aggregateId]);
        if (receipt) return { state, receipt };
        const checklist = (entity.checklist ?? []).map(canonicalizeChecklistItem);
        const itemId = cmd.arguments?.checklistItemId;
        const beforeId = cmd.arguments?.beforeItemId ?? null;
        const from = checklist.findIndex((i) => i.id === itemId);
        if (from === -1) return { state, receipt: NOOP() };
        const [item] = checklist.splice(from, 1);
        const to = beforeId === null ? checklist.length : checklist.findIndex((i) => i.id === beforeId);
        if (to === -1) return { state, receipt: NOOP() };
        checklist.splice(to, 0, item);
        if (JSON.stringify(checklist) === JSON.stringify(entity.checklist)) return { state, receipt: NOOP() };
        return setField(state, cmd.aggregateId, { checklist });
    },
};

function updateChecklistItem(state, cmd, updater) {
    const { entity, receipt } = findActive(state.tasks[cmd.aggregateId]);
    if (receipt) return { state, receipt };
    const checklist = entity.checklist ?? [];
    const itemId = cmd.arguments?.checklistItemId;
    const idx = checklist.findIndex((i) => i.id === itemId);
    if (idx === -1) return { state, receipt: NOOP() };
    const updated = updater(checklist[idx], checklist);
    if (updated === checklist[idx]) return { state, receipt: NOOP() };
    const next = Array.isArray(updated) ? updated : checklist.map((i, k) => (k === idx ? updated : i));
    return setField(state, cmd.aggregateId, { checklist: next });
}

function updateInMap(state, mapKey, id, updater) {
    const map = state[mapKey];
    const entity = map[id];
    if (!entity) return { state, receipt: REJECTED('ENTITY_NOT_FOUND') };
    if (entity.lifecycle === 'deleted') return { state, receipt: { status: 'ENTITY_DELETED', errorCode: 'ENTITY_DELETED' } };
    const next = updater(entity);
    if (next === entity) return { state, receipt: NOOP() };
    return { state: { ...state, [mapKey]: { ...map, [id]: next } }, receipt: { status: 'APPLIED' } };
}

function deleteFromMap(state, mapKey, id, seq) {
    const map = state[mapKey];
    const entity = map[id];
    if (!entity) return { state, receipt: REJECTED('ENTITY_NOT_FOUND') };
    if (entity.lifecycle === 'deleted') return { state, receipt: NOOP('NOOP_ALREADY_DELETED') };
    return {
        state: { ...state, [mapKey]: { ...map, [id]: { ...entity, lifecycle: 'deleted', deletedAt: seq } } },
        receipt: { status: 'APPLIED' },
    };
}

const LIST = {
    'list.create'(state, cmd) {
        const id = cmd.aggregateId;
        if (!id) return { state, receipt: REJECTED('MISSING_AGGREGATE_ID') };
        if (state.customLists[id]) return { state, receipt: { status: 'ID_ALREADY_EXISTS', errorCode: 'ID_ALREADY_EXISTS' } };
        const args = cmd.arguments ?? {};
        const customLists = {
            ...state.customLists,
            [id]: {
                title: typeof args.title === 'string' ? args.title : '',
                color: typeof args.color === 'string' ? args.color : null,
                lifecycle: 'active',
                deletedAt: null,
            },
        };
        return { state: { ...state, customLists }, receipt: { status: 'APPLIED' } };
    },

    'list.rename'(state, cmd) {
        const title = String(cmd.arguments?.title ?? '');
        return updateInMap(state, 'customLists', cmd.aggregateId, (l) => (l.title === title ? l : { ...l, title }));
    },

    'list.setColor'(state, cmd) {
        const color = cmd.arguments?.color ?? null;
        return updateInMap(state, 'customLists', cmd.aggregateId, (l) => (l.color === color ? l : { ...l, color }));
    },

    'list.delete'(state, cmd, seq) {
        const id = cmd.aggregateId;
        const before = deleteFromMap(state, 'customLists', id, seq);
        if (before.receipt.status !== 'APPLIED') return before;
        const tasks = {};
        for (const [taskId, t] of Object.entries(before.state.tasks)) {
            if (t.listId === id) {
                tasks[taskId] = { ...t, listId: null };
            } else {
                tasks[taskId] = t;
            }
        }
        return { state: { ...before.state, tasks }, receipt: before.receipt };
    },
};

const JOURNAL = {
    'journal.setText'(state, cmd) {
        const id = cmd.aggregateId;
        if (!id) return { state, receipt: REJECTED('MISSING_AGGREGATE_ID') };
        const text = String(cmd.arguments?.text ?? '');
        const existing = state.journals[id];
        if (!existing) {
            const journals = { ...state.journals, [id]: { text, lifecycle: 'active', deletedAt: null } };
            return { state: { ...state, journals }, receipt: { status: 'APPLIED' } };
        }
        if (cmd.arguments?.ifMissing === true) return { state, receipt: NOOP() };
        return updateInMap(state, 'journals', id, (j) => (j.text === text ? j : { ...j, text }));
    },

    'journal.delete'(state, cmd, seq) {
        return deleteFromMap(state, 'journals', cmd.aggregateId, seq);
    },
};

const GOAL = {
    'goal.create'(state, cmd) {
        const id = cmd.aggregateId;
        if (!id) return { state, receipt: REJECTED('MISSING_AGGREGATE_ID') };
        if (state.goals[id]) return { state, receipt: { status: 'ID_ALREADY_EXISTS', errorCode: 'ID_ALREADY_EXISTS' } };
        const goals = {
            ...state.goals,
            [id]: { title: String(cmd.arguments?.title ?? ''), lifecycle: 'active', deletedAt: null },
        };
        return { state: { ...state, goals }, receipt: { status: 'APPLIED' } };
    },

    'goal.patch'(state, cmd) {
        const patch = cmd.arguments?.patch;
        if (typeof patch !== 'object' || patch === null || Array.isArray(patch)) {
            return { state, receipt: REJECTED('INVALID_PATCH') };
        }
        const { lifecycle, deletedAt, ...safe } = patch;
        return updateInMap(state, 'goals', cmd.aggregateId, (g) => {
            const next = { ...g, ...safe };
            return JSON.stringify(next) === JSON.stringify(g) ? g : next;
        });
    },

    'goal.delete'(state, cmd, seq) {
        return deleteFromMap(state, 'goals', cmd.aggregateId, seq);
    },
};

const INSIGHT = {
    'insight.upsert'(state, cmd) {
        const id = cmd.aggregateId;
        if (!id) return { state, receipt: REJECTED('MISSING_AGGREGATE_ID') };
        const existing = state.insights[id];
        if (existing?.lifecycle === 'deleted') {
            return { state, receipt: { status: 'ENTITY_DELETED', errorCode: 'ENTITY_DELETED' } };
        }
        const payload = cmd.arguments?.payload;
        if (typeof payload !== 'object' || payload === null || Array.isArray(payload)) {
            return { state, receipt: REJECTED('INVALID_PAYLOAD') };
        }
        const { lifecycle, deletedAt, ...safe } = payload;
        const entity = { ...safe, lifecycle: 'active', deletedAt: null };
        if (existing && JSON.stringify(existing) === JSON.stringify(entity)) return { state, receipt: NOOP() };
        return { state: { ...state, insights: { ...state.insights, [id]: entity } }, receipt: { status: 'APPLIED' } };
    },

    'insight.delete'(state, cmd, seq) {
        return deleteFromMap(state, 'insights', cmd.aggregateId, seq);
    },
};

const HANDLERS = { ...TASK, ...CHECKLIST, ...LIST, ...JOURNAL, ...GOAL, ...INSIGHT };

export function applyCommand(state, command, brokerSequence) {
    const handler = HANDLERS[command?.type];
    if (!handler) {
        return { state, receipt: { status: 'REJECTED', errorCode: 'SCHEMA_UNSUPPORTED' } };
    }
    return handler(state, command, brokerSequence);
}
