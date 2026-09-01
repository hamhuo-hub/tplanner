# tPlanner

## Android / Wear 8.0.0

本分支是 Android 手机与 Wear OS 的 `8.0.0` 发布线；正式标签为
`mobile_8.0.0`。手机、手表应用和两款表盘共享 `shared` 中的设计令牌与同步协议。

同步 UI 遵守单向数据流：用户手势、冷启动或远端通知只向进程级
`SyncCoordinator` 提交请求；`operationId` 标识真实事务，页面只观察
`saved → uploading → updating → success/error(ERRORxxx)`。切换 Notes / Inbox / Timeline、
Composable 重组或状态恢复都不得由动画反向触发网络请求。Phone 的 Pull-to-Sync 边界只有
一个，业务事务进行中切 Tab 只会继续展示同一个 `operationId`。

Wear 应用和表盘读取同一个已提交投影。投影安装通过 SharedPreferences 监听即时通知活动
Renderer 并 `invalidate()`；60 秒重读仅是进程异常恢复兜底。系统表盘选择器直接引用
`drawable-nodpi` 静态 PNG，避免三星选择器无法展开 vector/layer-list 而显示黑屏。

发布前仍需完成两项真机验收（JVM 无法覆盖系统 hit-test 与厂商 picker）：

- 全屏 Note 编辑器的标题、正文间距、四周 padding 和空白区域均吞掉触摸，背后详情页不得响应。
- 手表系统 picker 与 Galaxy Wearable 中 Tide / Next 均显示真实缩略图；投影安装后手表应用与活动表盘应在同一次提交后立即刷新。

品牌资源说明见 `design-assets/README.md`。修改 launcher 或表盘预览后运行：

```powershell
pwsh scripts/check-android-brand-assets.ps1
pwsh scripts/generate-watch-previews.ps1
```

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

> **tag 命名约定**：master（桌面版）用 `v*`，mobile_andorid（Android）用 `mobile_*`，
> 两者互不重叠、标签不会互相触发对方的工作流。历史前缀 `TUI_*`/`desktop_*`（桌面）、
> `PUKEKO_*`（Android）仅作 `git describe` 兜底，不再用于新发版。

1. 本次发版 = 打 tag：`.\scripts\release.ps1 8.0.0`（可选 `-Push` 推送远程），生成 `mobile_8.0.0`
2. 构建时由根 `build.gradle.kts` 用 `git describe` 推导，`:app` / `:wear` 自动引用：
   - HEAD 恰好在 tag 上 → `versionName = 8.0.0`，`versionCode = 8000`（主×1000 + 次×100 + 补丁）
   - HEAD 在 tag 之后 → `versionName = 8.0.0-dev`（开发版，可带 `-dirty`）
3. 查看将生成的版本：`.\gradlew.bat printVersion`

