# tPlanner for Android

Android 端，配合桌面端使用。手机负责随手记、任务管理、焦虑追踪；手表提供三款表盘并一键蓝牙唤醒手机。

## 手机 APP

### 随手记
日常日记编辑，支持 Markdown 渲染预览，与桌面端通过同步服务器合并。

### 日程任务
今日事件按「进行中 / 稍后 / 已过 / 已完成」分组，支持勾选完成、左滑删除、新建事件。

### 焦虑追踪
手表唤醒后弹出全屏面板：记录想法、选择情绪/身体症状/焦虑强度，本地实时关键词检测 12 类认知扭曲，提交后调 DeepSeek API 自动做 CBT 三栏分析（自动思维 / 思维钢印 / 理智反思）。

### 洞察面板
每日焦虑事件统计、思维钢印分布、高发地点与时段、AI 日终复盘。

### 多设备同步
与桌面端共用同一同步服务器（`https://sync.hamhuo.top`），updatedAt-wins 合并、tombstone 软删除传播。

## 手表唤醒

手机端蓝牙前台服务常驻（开机自启），手表端通过经典蓝牙 RFCOMM 直连（绕开国行三星不可用的 GMS Wearable Data Layer）。唤醒后自动打时间戳 + GPS 到日记，弹出焦虑记录面板。

## Wear OS 表盘

三款设计，暗底黑金配色，与桌面端一致：

- **时环** — 24h 金色进度环 + 事件刻度 + 下一个事件标题
- **星轨** — 事件星座虚线连线 + 单针走时
- **余烬** — 纯排印时/分上下堆叠 + 呼吸光环

三款底部均可点按唤醒手机（震动反馈 + 蓝牙直连）。

## 技术栈

| 层 | 技术 |
|----|------|
| UI | Jetpack Compose (Material 3) |
| 存储 | SharedPreferences + JSON |
| 同步 | HttpURLConnection（无三方依赖） |
| AI | DeepSeek API（直连，不经过树莓派） |
| 定位 | 高德 Web API 逆地理编码 |
| 蓝牙 | 经典蓝牙 RFCOMM (免 GMS) |
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
├── AmapGeocoder.kt               高德逆地理编码
├── LanSyncManager.kt             同步管理器
├── PhoneTabBar.kt                底部页签栏
├── BootReceiver.kt               开机自启
└── BluetoothWakeService.kt       蓝牙前台服务（手表唤醒）
```

## License

Private — 保留所有权利
