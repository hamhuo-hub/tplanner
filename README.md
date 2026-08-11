# tPlanner for Android

Android 端，配合桌面端使用。手机负责随手记、任务管理、焦虑追踪；手表提供潮汐 / 下一项表盘与清单管理。

## 手机 APP

### 随手记
日常日记编辑，支持 Markdown 渲染预览，与桌面端通过同步服务器合并。

### 日程任务
今日事件按「进行中 / 稍后 / 已过 / 已完成」分组，支持勾选完成、左滑删除、新建事件。

手机主动打开 AI 日程整理面板时，应用按需申请前台定位并通过高德逆地理编码补充地点。定位不由手表触发，也不会在后台持续运行。

### 焦虑追踪
从手机端主动打开全屏面板：记录想法、选择情绪/身体症状/焦虑强度，本地实时关键词检测 12 类认知扭曲，提交后调 DeepSeek API 自动做 CBT 三栏分析（自动思维 / 思维钢印 / 理智反思）。

### 洞察面板
每日焦虑事件统计、思维钢印分布、高发地点与时段、AI 日终复盘。

### 多设备同步
与桌面端共用同一同步服务器（`https://sync.hamhuo.top`），updatedAt-wins 合并、tombstone 软删除传播。

## 手表通信

手机向手表推送日程、手表向手机新建事项时优先使用 GMS Wearable Data Layer；无 GMS 时降级为经典蓝牙 RFCOMM。表盘事项区只打开手表端应用，不会拉起手机界面。

## Wear OS 表盘

**潮汐**采用暗底黑金配色，以 24h 波浪展示时间进度，并在波形上标记当天日程。

**下一项**以左下时间轮和右上事项弧带组成斜切布局；点击右上事项区域可进入手表端清单应用。

## 技术栈

| 层 | 技术 |
|----|------|
| UI | Jetpack Compose (Material 3) |
| 存储 | SharedPreferences + JSON |
| 同步 | HttpURLConnection（无三方依赖） |
| AI | DeepSeek API（直连，不经过树莓派） |
| 定位 | 手机前台 LocationManager + 高德 Web API 逆地理编码 |
| 蓝牙 | 经典蓝牙 RFCOMM（手机 ↔ 手表事项同步，免 GMS） |
| 表盘 | Wear OS WatchFaceService + Canvas 自绘 |

## 构建

Android Studio 打开项目根目录，Gradle 同步后直接运行。

模块：
- `app` — 手机主模块
- `wear` — Wear OS 表盘模块

## 项目结构

```
app/src/main/java/com/hamhuo/tplanner/
├── MainActivity.kt              启动、主布局、同步触发
├── Models.kt                     数据模型 + 认知扭曲枚举
├── Theme.kt                      黑金配色
├── JournalPanel.kt               随手记编辑器 + 同步面板
├── JournalStore.kt               随笔持久化
├── TaskWidget.kt                 日程任务列表
├── AddEventFlow.kt               新建事件 + 事件详情页
├── EventStore.kt                 事件持久化
├── InsightPanel.kt               洞察统计面板
├── InsightStore.kt               焦虑事件 + 日终报告持久化
├── AnxietyInputSheet.kt          焦虑记录全屏面板
├── CognitiveDistortionDetector.kt 本地认知扭曲关键词检测
├── DeepSeekAnalysisService.kt    DeepSeek API 封装
├── LocationCapture.kt            手机整理面板的一次性前台定位
├── AppLocationStore.kt           本次手机定位的进程内一次性桥接
├── AmapGeocoder.kt               高德逆地理编码
├── LanSyncManager.kt             同步管理器
├── PhoneTabBar.kt                底部页签栏
├── BootReceiver.kt               开机自启
└── WatchTaskImportService.kt     手表新建事项的蓝牙接收服务
```

## License

Private — 保留所有权利
