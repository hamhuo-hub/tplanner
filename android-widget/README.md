# tPlanner 便签 — Android

今日便签的 Android 版，直接连接局域网同步服务器，无需其他依赖。

## 环境要求

- Node.js ≥ 18
- Android Studio（包含 Android SDK）
- JDK 17+

## 构建步骤

```bash
cd android-widget

# 安装 Capacitor
npm install

# 初始化 Android 项目（首次运行）
npx cap add android

# 同步 web 资源到 Android 项目
npx cap sync android

# 在 Android Studio 打开（之后点击 Run 即可安装到手机）
npx cap open android
```

### 直接构建 APK（命令行）

```bash
cd android-widget
npm install
npx cap add android
npx cap sync android
cd android && ./gradlew assembleDebug
# APK 路径：android/app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. 安装 APK 到 Android 设备
2. 首次打开，点击 ⚙ 设置服务器 IP（树莓派地址）和端口（默认 37401）
3. 数据自动从同步服务器加载
4. 下拉刷新 / 每 30 秒自动刷新

## 功能

- 今日事件按「进行中 / 稍后 / 已过」分组
- 已完成任务折叠（点击「已完成」标题展开/收起）
- 任务/子任务直接打勾，同步到服务器
- 底部随笔记录，800ms 防抖自动保存
- 支持下拉刷新

## 数据来源

直接读写局域网同步服务器（树莓派），不存本地数据库：
- `GET /tplanner/events` — 读取所有事件
- `PUT /tplanner/events` — 更新任务完成状态
- `GET /tplanner/journals` — 读取随笔
- `PUT /tplanner/journals` — 保存随笔
