# tPlanner

## 领域词汇

### 核心模型

| 术语 | 说明 |
|------|------|
| `ScheduleItem` | 所有事项的统称（原 `TaskEvent`） |
| `task` | 可完成的待办 |
| `event` | 有时间意义的事件 |
| `status` | 状态 / 占位 |
| `ItemType` | 类型枚举：`task` / `event` / `status` |

### 应用层

| 术语 | 说明 |
|------|------|
| `ScheduleItemStore` | 事项持久层（原 `EventStore`） |
| `ScheduleItemEditor` | 事项创建 + 编辑 UI（原 `AddEventFlow`） |
| `ScheduleItemDetailScreen` | 事项详情编辑页 |
| `CreateItemTypeSheet` | 新建时选择事项类型 |
| `ItemTypeChangeSheet` | 修改已有事项类型 |
| `ScheduleItemActions` | 事项操作（创建 / 编辑 / 删除） |
| `ScheduleItemUpdates` | 事项增量更新 |
| `ScheduleItemEditDraft` | 编辑草稿（原 `EventEditDraft`） |

### 持久层

| Kotlin | SQLite (物理) |
|--------|--------------|
| `ScheduleItemEntity` | `events` |
| `ScheduleItemDao` | `events` |

> 物理表名 `events` 保持不变，无需数据库迁移。

### 时间线

| 术语 | 说明 |
|------|------|
| `TimelineItemMetadata` | 事项在时间格中的布局元数据 |
| `TimelineItemCard` | 时间线事项卡片 |
| `TimelineItemLayer` | 时间线事项渲染层 |

### AI 辅助

| 术语 | 说明 |
|------|------|
| `ScheduleProposal` | AI 生成的排程建议 |

### 变量命名

| 旧 | 新 |
|----|----|
| `editingEvent` | `editingItem` |
| `beginNewEvent` | `beginNewItem` |
| `pendingNewEvent` | `pendingNewItem` |
| `eventConflict` | `itemConflict` |
| `openEvent` | `openItem` |

## 发版（版本号管理）

版本号以 git tag 为唯一来源，代码里不再手写：

1. 发版 = 打 tag：`.\scripts\release.ps1 6.0.2`（可选 `-Push` 推送远程），生成 `mobile_6.0.2`
2. 构建时由根 `build.gradle.kts` 用 `git describe` 推导，`:app` / `:wear` 自动引用：
   - HEAD 恰好在 tag 上 → `versionName = 6.0.2`，`versionCode = 6002`（主×1000 + 次×100 + 补丁）
   - HEAD 在 tag 之后 → `versionName = 6.0.2-dev`（开发版，可带 `-dirty`）
3. 查看将生成的版本：`.\gradlew.bat printVersion`

