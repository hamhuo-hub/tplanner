# 重复任务系列修复

## 用户要求与行为

- 重复任务必须保存系列关联，修改一个成员的共用内容会更新整组。
- 待办列表按系列展示下一次未完成事项；时间轴仍展示每次实际安排。每次完成状态、子项完成进度独立保存。
- 已有任务可以从详情设置每日/每周/每月重复，无需删除后重建。
- 不靠标题相同自动合并旧任务；仅有明确系列标识或可验证的旧实例 ID 关联时才恢复绑定。

## 同步契约

使用现有 V3 `task.setRecurrence` 的 recurrence 对象，不增加服务器命令，也不恢复被废弃的顶层 groupId 作为新写入格式：

```json
{
  "frequency": "daily",
  "count": 5,
  "seriesId": "最初成员的任务ID",
  "occurrenceIndex": 0,
  "anchorStartAt": "2026-09-08T01:00:00.000Z",
  "anchorEndAt": "2026-09-08T02:00:00.000Z",
  "timeZone": "Asia/Shanghai"
}
```

- frequency 为 daily/weekly/monthly；count 是系列总次数，1..50；occurrenceIndex 从 0 开始。
- 同系列保留同一个 seriesId；anchor/timeZone 表达第 0 次的排程。新成员 ID 必须稳定且不重用已删除任务 ID。
- Android 本地通过 extras 的 `_syncV3Recurrence` 保留完整对象，现有 recurrenceType/recurrenceCount 继续作为编辑投影；Web 使用 recurrence 原对象及同名编辑投影。
- 同步投影和无关字段编辑须保留对象扩展字段；设置重复为 none 时显式清理关联。
- 共用内容修改保持每个成员原 ID、完成状态及已有子项完成进度；仅完成/子项完成操作不传播至全组。
- 扩展次数只创建缺失成员，缩减次数不删除已完成历史；取消重复保留正在编辑的成员与已完成历史，撤销其余未完成排程。删单次与删整组不可暗中混用。
- 从非首个成员修改频率，以正在编辑的日期作为新排程的对齐点。修改日期或频率只重排未完成成员；已完成成员仅在用户直接修改它自己的日期时移动。
- `recurrence.retiredOccurrenceIds` 保存已撤销的成员 ID；取消重复后，保留成员通过 `extras._retiredRecurringOccurrenceIds` 延续退休记录。创建成员须同时避开现存及退休 ID，防止删除记录清理后重新使用同一 ID。
- 不因读取列表而写入或删除任务。系列折叠仅改变列表投影；时间轴、导出和底层数据仍保留各次实例。

## 历史数据与兼容边界

- Android 可恢复有确切 `nameUUIDFromBytes("root:recurrence:index")` 证据的旧成员；Web 也识别这种 ID，并在历史导入/迁移仍保留明确 groupId 时将其转为 recurrence 关联。
- 旧 Android 的持久化/同步映射和已执行的旧 Web 数据迁移会清除顶层 groupId。关联已经丢失、又使用随机 ID 的历史任务无法可靠自动合并；普通改名或直接保存不会再按旧次数生成一批副本。
- V3 服务器现有 recurrence 对象允许扩展字段，命令和 schema 无需升级。未知字段在投影、无关编辑和往返同步时保留。
- Phone→Wear 仍传送各次实际日程；仅 Dashboard 待办投影按系列折叠。新增可选系列元数据校验，保留旧 tasksHash 计算，兼容旧客户端。
- Web 同一适配器内的保存、通知刷新和日记同步串行执行，避免命令序号争用；刷新结果不能覆盖等待期间的本地修改。多浏览器标签页之间的共享存储原子性不在本次队列修复范围内。

## 文件所有权

| 负责人 | 范围 |
| --- | --- |
| Phone agent | Android recurrence factory/领域方法、RoomEventRepository/ScheduleItemStore、MainScreen/TaskWidget/TaskView/Editor 及必要文案 |
| Web agent | master worktree 的 recurrence 领域方法、App/Modal/列表/小窗、数据库 schema 与 V3 Web 转换 |
| Sync/Wear agent | Android V3 recurrence planner/projection、Phone→Wear 传输/过滤、Wear 数据投影与列表；不修改 Phone agent 文件 |
| 主 agent | 本记录、跨端契约协调、独立回归验证和集成修复 |

另一个并行任务处理圆角按钮按压反馈，已将 TaskWidget、PhoneTabBar 和 Timeline 控件的同形 clip 以 `1a43859` 独立提交并发布 `mobile_8.1.1`。本次重复任务改动未混入该发布；后续编辑保留这些裁剪。本次 Editor 的同形裁剪随编辑入口调整处理，不改设计令牌。

## 验证记录

- 浏览器实际组件离线验收：已有任务转每日三次，从第二次改名后全部三次更新；只完成第二次子项，三次进度分别保持 1/3、3/3、0/3；展开待办区只有一个系列代表，时间轴保留三次日程。示例数据仅在内存保存，未触及用户数据。
- Web/Desktop 最终 20 项重复任务回归、3 项同步队列回归和 `npm run build` 全部通过；Electron 小窗随构建复制共用列表投影模块。覆盖并发编辑、退休 ID、旧数据恢复、月末、夏令时、未知字段及取消后重建。
- 独立命令归约检查：同一套任务创建、设置完整系列、改名和完成命令，经 Web 本地及服务器实际 reducer 归约结果一致，扩展元数据保留。
- Android `:app:assembleDebug :app:lintDebug :wear:assembleDebug :wear:lintDebug` 全部通过；Phone 领域回归 48 项通过，Sync/Wear 三组生产代码回归通过。构建日志为 `build/recurring-series-build.log`。
- 当前没有连接真机或可用模拟器；已验证编译、lint、独立 JVM 行为及浏览器组件，尚未完成真机交互或生产账户端到端同步验收。

复查入口（不恢复已移除的测试框架）：

```text
Android 分支：python -B scripts/check-recurring-task-series.py
Android 分支（先完成 debug 编译）：python -B scripts/check-recurring-series-sync.py --java <JBR/java>
Web/Desktop 分支：node scripts/check-recurring-series.mjs
Web/Desktop 分支：node scripts/check-web-sync-queue.mjs
```
