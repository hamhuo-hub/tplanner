package com.hamhuo.tplanner

import org.json.JSONObject

// ── 数据库 ─────────────────────────────────────────────────────────────────
// 不再有"焦虑分析/洞察"相关模型（StructuredEntry/DayReport 已删除）。
// EventStore 各类型模型（TaskEvent, CheckItem 等）定义在各自使用方文件中。
// JournalEntry 全量定义见 JournalStore.kt。
