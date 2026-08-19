// 旧版 JSON 数据导入(见 docs/sync-v3.md §17 迁移流程第 2/6 步)。
// parseLegacyData:纯函数,解析 + 规范化(剥 groupId、统一 lifecycle、Inbox/Today 不会被导入成清单);
// importIntoDatabase:单事务写入 entities 表,INSERT OR IGNORE 保证可安全重跑。
//
// 旧格式:
//   events.json   数组 [{ id, payload, updatedAt, deletedAt }]
//   journals.json 映射 { date: { text, updatedAt, deletedAt } | "旧版纯字符串" }
//   goals.json    数组 [{ id, payload, updatedAt, deletedAt }]
//   insights.json { entries: [{ id, payload, ... }], reports: { date: { ...payload } } }

export function parseLegacyData({ events = [], journals = {}, goals = [], insights = {} } = {}) {
  const entities = [];
  const issues = [];
  const seen = new Set();

  const push = (entityType, entityId, payload, updatedAt, deletedAt) => {
    if (entityId == null || entityId === '') {
      issues.push(`${entityType}: empty id, skipped`);
      return;
    }
    const key = `${entityType}:${String(entityId)}`;
    if (seen.has(key)) {
      issues.push(`${key}: duplicate id, skipped`);
      return;
    }
    seen.add(key);

    const clean = { ...(payload ?? {}) };
    delete clean.groupId;                       // groupId 不进 V3(不变量 #9)
    if (entityType === 'task' && !clean.itemType) clean.itemType = 'task';

    entities.push({
      entityType,
      entityId: String(entityId),
      lifecycle: deletedAt ? 'deleted' : 'active',
      payload: clean,
      updatedAt: Number(updatedAt) || 0,
      deletedAt: deletedAt ? Number(deletedAt) : null,
    });
  };

  for (const e of Array.isArray(events) ? events : []) {
    push('task', e?.id, e?.payload, e?.updatedAt, e?.deletedAt);
  }

  for (const [date, entry] of Object.entries(journals ?? {})) {
    const value = typeof entry === 'string' ? { text: entry, updatedAt: 0, deletedAt: null } : entry;
    push('journal', date, { text: value?.text ?? '' }, value?.updatedAt, value?.deletedAt);
  }

  for (const g of Array.isArray(goals) ? goals : []) {
    push('goal', g?.id, g?.payload, g?.updatedAt, g?.deletedAt);
  }

  for (const i of Array.isArray(insights?.entries) ? insights.entries : []) {
    push('insight', i?.id, i?.payload, i?.updatedAt, i?.deletedAt);
  }
  for (const [date, r] of Object.entries(insights?.reports ?? {})) {
    push('insight', `report-${date}`, { date, ...(r?.payload ?? r) }, r?.updatedAt, r?.deletedAt);
  }

  return { entities, issues };
}

export function importIntoDatabase(db, entities) {
  const insert = db.prepare(`
    INSERT OR IGNORE INTO entities
      (entity_type, entity_id, lifecycle, payload_json, last_broker_sequence,
       created_at, updated_at, deleted_at)
    VALUES (?, ?, ?, ?, 0, ?, ?, ?)
  `);
  const run = db.transaction((rows) => {
    let written = 0;
    for (const r of rows) {
      written += insert.run(
        r.entityType,
        r.entityId,
        r.lifecycle,
        JSON.stringify(r.payload),
        r.updatedAt,
        r.updatedAt,
        r.deletedAt,
      ).changes;
    }
    return written;
  });
  return run(entities);
}
