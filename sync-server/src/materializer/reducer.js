// 确定性中央 reducer —— 业务规则的唯一权威(见 docs/sync-v3.md §5)。
//
// 铁律:
//   - 纯函数:不读时钟、不随机。状态内所有"时间"来自命令流(broker sequence)。
//   - 内部错误必须 throw(由 materializer 停止消费、等待重投,绝不静默跳过);
//     业务拒绝返回 receipt,不抛异常。
//   - 删除是生命周期:普通编辑命中已删实体返回 ENTITY_DELETED,只有 restore 能恢复。
//   - 重复设置相同值返回 NOOP,不产生状态变化。
//
// 实体规范:task = { title, note, completed, itemType, [schedule], [recurrence],
//   [listId], [checklist], lifecycle, deletedAt }
// 字段按需出现(未被命令触及的字段不写入),保证与 fixtures 逐键一致。

export function emptyState() {
  return { tasks: {}, customLists: {}, journals: {}, goals: {}, insights: {} };
}

const REJECTED = (errorCode) => ({ status: 'REJECTED', errorCode });
const NOOP = (errorCode) => ({ status: 'NOOP', ...(errorCode ? { errorCode } : {}) });

function findActive(entity) {
  if (!entity) return { receipt: REJECTED('ENTITY_NOT_FOUND') };
  if (entity.lifecycle === 'deleted') return { receipt: { status: 'ENTITY_DELETED', errorCode: 'ENTITY_DELETED' } };
  return { entity };
}

function setField(state, id, patch) {
  const { entity, receipt } = findActive(state.tasks[id]);
  if (receipt) return { state, receipt };
  const tasks = { ...state.tasks, [id]: { ...entity, ...patch } };
  return { state: { ...state, tasks }, receipt: { status: 'APPLIED' } };
}

function updateTask(state, id, updater) {
  const { entity, receipt } = findActive(state.tasks[id]);
  if (receipt) return { state, receipt };
  const next = updater(entity);
  if (next === entity) return { state, receipt: NOOP() };
  const tasks = { ...state.tasks, [id]: next };
  return { state: { ...state, tasks }, receipt: { status: 'APPLIED' } };
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
        title: typeof args.title === 'string' ? args.title : '',
        note: '',
        completed: false,
        itemType: args.itemType ?? 'task',
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
    return updateTask(state, cmd.aggregateId, (t) =>
      JSON.stringify(t.schedule) === JSON.stringify(schedule) ? t : { ...t, schedule });
  },

  'task.setRecurrence'(state, cmd) {
    const recurrence = cmd.arguments?.recurrence ?? null;
    return updateTask(state, cmd.aggregateId, (t) =>
      JSON.stringify(t.recurrence) === JSON.stringify(recurrence) ? t : { ...t, recurrence });
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
    return updateTask(state, cmd.aggregateId, (t) => (t.listId === listId ? t : { ...t, listId }));
  },

  'task.moveInTimeline'(state, cmd) {
    const offsetMinutes = Number(cmd.arguments?.offsetMinutes);
    if (!Number.isFinite(offsetMinutes)) return { state, receipt: REJECTED('MISSING_OFFSET') };
    return updateTask(state, cmd.aggregateId, (t) => {
      if (!t.schedule?.startAt) return t; // 无排程:相对位移无处可施,NOOP
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
    const tasks = { ...state.tasks, [cmd.aggregateId]: { ...t, lifecycle: 'deleted', deletedAt: seq } };
    return { state: { ...state, tasks }, receipt: { status: 'APPLIED' } };
  },

  'task.restore'(state, cmd) {
    const t = state.tasks[cmd.aggregateId];
    if (!t) return { state, receipt: REJECTED('ENTITY_NOT_FOUND') };
    if (t.lifecycle === 'active') return { state, receipt: NOOP() };
    const tasks = { ...state.tasks, [cmd.aggregateId]: { ...t, lifecycle: 'active', deletedAt: null } };
    return { state: { ...state, tasks }, receipt: { status: 'APPLIED' } };
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
    const item = { id: itemId, title: typeof args.title === 'string' ? args.title : '', completed: false };
    return setField(state, cmd.aggregateId, { checklist: [...checklist, item] });
  },

  'checklist.setTitle'(state, cmd) {
    const title = String(cmd.arguments?.title ?? '');
    return updateChecklistItem(state, cmd, (item) => (item.title === title ? item : { ...item, title }));
  },

  'checklist.setCompleted'(state, cmd) {
    const completed = Boolean(cmd.arguments?.completed);
    return updateChecklistItem(state, cmd, (item) => (item.completed === completed ? item : { ...item, completed }));
  },

  'checklist.deleteItem'(state, cmd) {
    return updateChecklistItem(state, cmd, (item, checklist) => checklist.filter((i) => i.id !== item.id));
  },

  // token 语义:移到 beforeItemId 之前(beforeItemId 为 null 表示移到最后),不用绝对下标
  'checklist.reorderItem'(state, cmd) {
    const { entity, receipt } = findActive(state.tasks[cmd.aggregateId]);
    if (receipt) return { state, receipt };
    const checklist = [...(entity.checklist ?? [])]; // 拷贝,绝不在旧状态上原地修改
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

  // 删除自定义清单:其任务统一转为未分配(Inbox/Today 只是视图,不参与)
  'list.delete'(state, cmd, seq) {
    const id = cmd.aggregateId;
    const before = deleteFromMap(state, 'customLists', id, seq);
    if (before.receipt.status !== 'APPLIED') return before;
    const tasks = {};
    for (const [taskId, t] of Object.entries(before.state.tasks)) {
      if (t.listId === id) {
        const { listId, ...rest } = t;
        tasks[taskId] = rest;
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
      [id]: {
        title: String(cmd.arguments?.title ?? ''),
        lifecycle: 'active',
        deletedAt: null,
      },
    };
    return { state: { ...state, goals }, receipt: { status: 'APPLIED' } };
  },

  'goal.patch'(state, cmd) {
    const patch = cmd.arguments?.patch;
    if (typeof patch !== 'object' || patch === null || Array.isArray(patch)) {
      return { state, receipt: REJECTED('INVALID_PATCH') };
    }
    const { lifecycle, deletedAt, ...safe } = patch; // 生命周期字段不可被 patch 篡改
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
    const insights = { ...state.insights, [id]: entity };
    return { state: { ...state, insights }, receipt: { status: 'APPLIED' } };
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
