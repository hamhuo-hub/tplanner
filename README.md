# tPlanner

## Android / Wear 8.0.0

本分支是 Android 手机与 Wear OS 的 `8.0.0` 发布线；正式标签为
`mobile_8.0.0`。手机、手表应用和两款表盘共享 `shared` 中的设计令牌与同步协议。

同步 UI 遵守单向数据流：用户手势、冷启动或远端通知只向进程级
`SyncCoordinator` 提交请求；`operationId` 标识真实事务，页面只观察
`saved → uploading → updating → success/error(ERRORxxx)`。切换 Notes / Inbox / Timeline、
Composable 重组或状态恢复都不得由动画反向触发网络请求。Phone 的 Pull-to-Sync 边界只有
一个，业务事务进行中切 Tab 只会继续展示同一个 `operationId`。

Android 自动备份保留 Room 中尚未完成的 semantic command，但排除服务器地址、认证与
传输游标。恢复到新安装后，应用先生成新的设备身份，再为所有保留命令分配新的稳定
`commandId`、`batchId` 和从 1 开始的连续 sequence，归档旧回执并强制重新安装中央基线；
旧 Watch 重试通过 command alias 仍可命中同一业务意图。上传器只接受包含匹配
`batchId`、`BROKER_PERSISTED` 和正整数 `brokerSequence` 的 202 回应，残缺 ACK 不得推进
本地状态。

Wear 应用和表盘读取同一个已提交投影。投影安装通过 SharedPreferences 监听即时通知活动
Renderer 并 `invalidate()`；60 秒重读仅是进程异常恢复兜底。系统表盘选择器直接引用
`drawable-nodpi` 静态 PNG，避免三星选择器无法展开 vector/layer-list 而显示黑屏。

8.0.0 的 Watch 写入桥只传 V3 semantic command，不写 legacy dataset，也不在手机本地
伪造权威事项。每个 watch envelope 携带稳定的 UUIDv7 child commandId；一次连接会批量发送
全部 pending，Data Layer 与 RFCOMM 在手机端共享持久幂等账本。回执有严格的两阶段含义：

- `PHONE_STORED`：手机已把完整命令批原子写入 V3 outbox；手表仍保留 pending。
- `SNAPSHOT_PUBLISHED`：中央回执已指向一个全局快照；只有手表又原子安装了来源版本不低于
  该快照的 projection，才终结 pending。

因此断连、进程重启、双通道重复投递、先到 final ACK 后到 projection 都不会丢命令或短暂
删除乐观事项。Phone → Watch projection 的 `sourceSnapshotVersion` 来自 V3 displayed/global
snapshot，而不是手机本地时间或旧数据集。

滚动升级边界同样属于发布契约：

- 8.0.0 的同步网络只访问 `/tplanner/v3/*`。`LegacyPreferencesImporter`、
  `v3_cutover_intents` 和 Watch schema-v1 decoder 只保留一版，用于离线数据与持久队列升级；
  它们不得发起或恢复 legacy dataset GET/PUT。
- Watch v1 pending/failed 队列在一次 commit 中升级；同一任务的 pending create → delete 会补上
  持久 `dependsOnRequestId`。任务隐藏集合只由已成功提交的 pending delete 派生，入队失败会立即
  恢复卡片，重启后仍从同一队列恢复。Delete 在 predecessor 的 `PHONE_STORED` 前不得发送，
  create/delete pending 都只能由匹配的中央 projection 终结。

发布前仍需完成以下真机验收（JVM 无法覆盖系统 hit-test 与厂商 picker）：

- 全屏 Note 编辑器的标题、正文间距、四周 padding 和空白区域均吞掉触摸，背后详情页不得响应。
- 手表系统 picker 与 Galaxy Wearable 中 Tide / Next 均显示真实缩略图；投影安装后手表应用与活动表盘应在同一次提交后立即刷新。
- 关闭 GMS 或断开蓝牙后连续新建至少 3 项，pending 均保留；重新连接一次应批量上行，
  `PHONE_STORED` 后仍可见，收到匹配 projection 后恰好各终结一次。
- 同一 envelope 同时经 Data Layer 与 RFCOMM 到达时，服务器只出现一组 child command；
  手机进程重启后重复发送仍返回同一阶段，不产生第二组命令。

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

