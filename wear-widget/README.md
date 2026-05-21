# tPlanner Wear — Samsung Galaxy Watch (Wear OS 3+)

今日便签的 Wear OS 版，含表盘和 Tile。

## 模块说明

| 模块 | 说明 |
|------|------|
| `mobile/` | 手机伴侣 — 定期从同步服务器拉取数据，通过蓝牙 Data Layer 推送到手表；接收手表的任务打勾请求并转发到服务器 |
| `wear/`   | 手表应用 — 表盘 + Tile，接收数据展示，支持任务打勾 |

## 字体

表盘使用 **Oswald**（苏联构成主义风格）。

```bash
# 下载 Oswald Regular TTF（来自 Google Fonts）放入：
wear/src/main/res/font/oswald_regular.ttf
```

若未放置，自动回退到系统默认粗体。

## 构建

```bash
cd wear-widget
./gradlew assembleDebug

# 安装手机伴侣
adb install mobile/build/outputs/apk/debug/mobile-debug.apk

# 安装手表 app（手表通过 adb 连接或 Android Studio）
adb -s <watch-serial> install wear/build/outputs/apk/debug/wear-debug.apk
```

## 配置同步服务器

手机端安装后，在设置页填写树莓派 IP 和端口（37401）。
同步间隔：15 分钟（WorkManager 周期任务），锁屏后由系统 Bluetooth 保活。

## 同步流程

```
RxDB（主日历）
    ↓ events:sync（原有流程）
同步服务器（树莓派）
    ↓ SyncWorker（手机，每15分钟）
Wearable Data Layer（蓝牙）
    ↓ DataLayerService（手表）
SharedPreferences（手表本地缓存）
    ↓
表盘 / Tile
```

## 表盘设计

- 深黑背景 `#0A0A0A`
- 左侧金色竖条（构成主义网格）
- 大号 Oswald 时间（HH / MM 上下排列）
- 金色构成主义冒号点
- 当前/下一个事件名称
- 右下角任务进度弧（绿色）
- 息屏模式自动简化
