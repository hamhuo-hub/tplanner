# tPlanner 仓库摘要缓存

> 本文件是仓库速查摘要,供后续 agent 会话省 token 用。详细领域词汇表见 `README.md`(ScheduleItem = 原 TaskEvent 等重命名对照)。

## 项目一句话

手机 + Wear OS 的时间线排程/待办应用(单仓库双端),AI(DeepSeek)辅助排程,支持手机↔手表同步与手表表盘展示。

## 模块与构建

- Gradle 多模块:`settings.gradle.kts` 只 include `:app`(手机)和 `:wear`(Wear OS)。
- `shared/` 不是 Gradle 模块:app 和 wear 都通过 `sourceSets` 把 `shared/src/main/kotlin` 直接加进自己的源码集(共享代码编译进两个 APK,注意两端各有一份)。
- 技术栈:Kotlin + Jetpack Compose(BOM + Material3)、Room + KSP、WorkManager、DataStore Preferences、GMS Wearable Data Layer(国际版 Wear OS),国行无 GMS 时走经典蓝牙 RFCOMM 兜底。
- 构建:`gradlew.bat assembleDebug`(Windows)。签名用项目 keystore(`keystore.properties`),手机和手表同一把 keystore,否则 Wearable Data Layer 不认。
- 版本:git tag 是唯一版本源 —— 根 `build.gradle.kts` 用 `git describe`(match `v*` + 历史 `PUKEKO_*`)推导 versionName/versionCode,`:app`/`:wear` 都从 `rootProject.extra` 读;公式 versionCode = 主×1000+次×100+补丁;HEAD 不在 tag 上时 versionName 带 `-dev`。发版用 `scripts/release.ps1 6.0.2 [-Push]`(打 tag `v6.0.2`),查版本用 `gradlew printVersion`。当前 HEAD 距 PUKEKO_6.0.1 有 24 个提交 → 现构建为 `6.0.1-dev` / 6001。minSdk 26,compileSdk/targetSdk 35,Java 17,Gradle 9.6.1。
- 密钥:`local.properties` 里的 `deepseek.api.key` 和 `amap.api.key`(不提交 VCS);也可用环境变量 `DEEPSEEK_API_KEY`。这些被注入 `BuildConfig`。
- 根目录的 `node_modules/`、`dist/`、`dist-electron/`、`package-lock.json` 是早期 Web/Electron 版本遗留(依赖 rxdb),当前主力是 Android;根目录无 package.json。

## app 模块包地图(`app/src/main/java/com/hamhuo/tplanner/`)

- `data/`:`ScheduleItemStore`(事项持久层)、`JournalStore`/`JournalDayRollover`(日志)、`TaskView`、`AppTime`、`ScheduleItemUpdates`。`Models.kt` 只是索引注释,模型分散在各使用方文件。
- `persistence/`:Room 全套 —— `TPlannerDatabase`、`PersistenceEntities`/`PersistenceDaos`/`RoomRepositories`、`SettingsRepository`、`DurableWriteQueue`(持久写队列)、`VersionedDraft`、`WireMappers`、`LegacyPreferencesImporter`。物理表名 `events` 保持不变(无迁移)。
- `timeline/`:时间线核心 —— `TimelineScreen`/`TimelineState`/`TimelineGeometry`/`TimelineLayoutEngine`/`TimelinePlacementMapper`/`TimelineItemMetadata`/`TimelineDragCalculator`/`TimelineDateWindow`/`TimelineEffects` + `components/`(TimelineGrid、TimelineItemCard、TimelineItemLayer、TimelineBody、TimelineDayHeader、ConflictBadge、TimelineStatusStrip、TimelineAddButton)。
- `ui/`:`MainActivity`/`MainScreen`/`MainLayout`/`PhoneTabBar`、`ScheduleItemEditor`(原 AddEventFlow)/`ScheduleItemCreation`、`JournalPanel`、`Theme`、`components/TPlannerComponents`、`TPlannerPullToSync`、`ListPickerSheet`、`UntangleSheet`、`TaskWidget`。
- `sync/`:局域网同步 `LanSyncManager`、`SyncDelta`、`SyncOutboxWorker`(WorkManager)、`RemoteChangeMonitor`、`ManualSyncPresentationTiming`;手表桥接 `WatchScheduleSync`/`WatchScheduleSyncJobService`/`WatchScheduleSnapshotProvider`/`WatchScheduleRefreshService`/`WatchTaskDataLayerService`/`WatchTaskImportReceiver`/`WatchTaskImportService`、`ScheduleIntentRouter`。
- `alarm/`:`TaskAlarmScheduler`、`TaskAlarmReceiver`、`AlarmRestoreReceiver`(重启恢复闹钟)。
- `ai/`:`DeepSeekAnalysisService`(AI 排程建议 `ScheduleProposal`)。
- `location/`:`AmapGeocoder`(高德)、`AppLocationStore`、`LocationCapture`。
- `actions/`:`ScheduleItemActions`、`RecurringTaskFactory`(独立循环任务)、`JournalActions`。
- 测试:`app/src/test`(LanSyncDeltaTest、ManualSyncPresentationTimingTest、RecurringTaskFactoryTest、ScheduleTimeNormalizerTest;JUnit + json)。

## wear 模块(`wear/src/main/kotlin/com/hamhuo/tplanner/`)

- `ui/`:`MainActivity`、`NextDashboardView`(下一事项仪表盘)、`WatchListActivities`、`RotaryInput`、`WearUiStyle`。
- `create/`:多步创建向导(每个字段一个 Activity)—— `CreateTitle/Type/Time/Date/SettingsActivity` + `CreationRoute`/`CreationViews`/`WatchTaskDraft`。
- `watchface/`:表盘 —— `FaceBase`/`FaceDesign`/`FaceNext`/`FaceTide` + `TPlannerWatchFaces`。
- `sync/`:蓝牙 RFCOMM 桥 —— `BluetoothScheduleBridgeService`/`BluetoothScheduleBridgeReceiver`、`ScheduleReceiverService`、`WatchManualSync`、`WatchTaskOutbox`、`WatchTaskAckReceiverService`。
- `data/`:`WatchEventMarks`、`WearLocale`、`WearTime`。

## shared 模块(`shared/src/main/kotlin/com/hamhuo/tplanner/`)

- 协议:`ScheduleRfcommProtocol`(蓝牙同步协议)、`WatchTaskProtocol`、`WatchScheduleRefreshProtocol`、`WatchSyncErrorCode`。
- `designsystem/`:两端共享的 `TPlannerDesignTokens`(颜色/字体/几何)、`TPlannerTaskUnitView`、`TPlannerSyncFeedbackView`(最近改动重点,见未推送提交)。

## git 状态(截至本次读取)

- 分支:`master`(origin/master)、`mobile_andorid`(当前,领先 origin 1 个提交)、`sync_server`(check out 在 worktree,见 `.claude/worktrees/`)。
- 未推送提交:`7fa3f81 fix(phone): show sync result after refresh animation`(改 ManualSyncPresentationTiming、MainScreen、TPlannerComponents、TPlannerSyncFeedbackView、NextDashboardView 等,含测试与 strings)。
- 近期主线:perf(sync) 发送 Android 变更 delta、AI 提取锚定当前时间、独立循环任务、长按时间线建任务、可点击状态条。

## 约定提示

- 领域词汇一律用新命名(ScheduleItem/ItemType/ScheduleItemStore…),旧名见 README 对照表。
- 手表同步双通道:有 GMS 走 Wearable Data Layer,否则 RFCOMM 兜底;两端签名必须同一 keystore。
