# tPlanner

> 轻量级本地时间线规划工具，支持跨平台桌面运行与局域网实时同步

![Version](https://img.shields.io/badge/version-2.0.0-gold)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Linux-blue)
![License](https://img.shields.io/badge/license-Private-grey)

---

## 功能概览

### 时间线视图
- 无限滚动时间轴，以「行 = 天」的形式展示所有事件
- 拖拽事件块即可调整时间（10 分钟对齐吸附）
- 多事件自动分道（Lane），永不重叠
- 时区切换：本地时间 / 北京 / 奥克兰 (NZST/NZDT) 等多时区支持

### 三种事件类型

| 类型 | 说明 | 全天选项 |
|------|------|---------|
| **提醒**（Reminder）| 带时间的计划事项，支持即将开始通知 | — |
| **状态**（Status）  | 顶部色带，标记时间段（如出行、假期） | ✓ |
| **任务**（Task）    | 带完成勾选，支持子任务清单 | ✓ |

### 子任务
- 任务可添加多个子任务
- 子任务未全部完成时，主任务勾选框锁定
- 所有子任务完成后自动勾选主任务
- 日历格内显示进度徽章（如 `2/5`）

### Banner 提醒系统
页面顶部三条横幅，使用同一套交互逻辑：

- 🔔 **即将到来的提醒** — 未来 90 分钟内开始的 Reminder 事件
- ⏰ **即将截止** — 所有未完成的 Task / Reminder，显示「还剩 N 天 / 明天到期 / 今天到期」倒计时
- ⚠️ **已逾期任务** — 截止时间已过但未完成的 Task
- ❌ **日程冲突** — 同类型事件时间重叠检测

### 今日便签（Widget）
独立悬浮窗口，随时查看今日事项：

- 分组展示「进行中 / 稍后 / 已过」
- 任务支持直接勾选，与主界面实时同步
- 子任务层级展示，点击进度徽章折叠/展开
- **今日随笔**：底部文本区，记录当天想法，与主界面日历联动

### 日期随笔
- 鼠标点击时间轴左侧日期列，弹出该天的随笔编辑框
- 有内容的日期显示金色小圆点指示
- 数据通过 IPC 持久化，便签窗口与主界面同步

### 局域网同步
- 一键**扫描局域网**，自动发现运行同步服务器的设备
- 点选设备后显示**冲突预览**：新增 / 推送 / 覆盖 / 保留各类事件数量及详情
- 按 `updatedAt` 自动合并，较新版本胜出
- 支持**自动同步**（可配置间隔）
- 可同时开启本机服务，接受其他设备推送

### 主题系统
- 支持 `.tptheme` 主题包（JSON 格式）
- 双击文件即可安装，或通过界面内主题管理器安装
- 内置主题：`blue-archive`、`reverse-1999`

### 其他
- 中英文界面切换
- 事件导入 / 导出（JSON）
- 循环事件（每天 / 每周 / 每月，最多 50 次）
- 打印视图
- 缩放控制（50%–200%）
- **调试面板**（F12）：实时日志 + 性能监控

---

## 安装与运行

### 直接下载（推荐）

从 `release/` 目录获取预构建版本：

| 平台 | 格式 | 说明 |
|------|------|------|
| Windows | `.exe`（安装版） | NSIS 安装包，支持自定义路径 |
| Windows | `tplanner-portable-*.exe` | 便携版，无需安装 |
| Linux | `.AppImage` | 通用格式，下载后直接运行 |
| Linux | `.deb` | Debian / Ubuntu 安装包 |

```bash
# Linux AppImage
chmod +x tplanner-*.AppImage && ./tplanner-*.AppImage

# Linux deb
sudo dpkg -i tplanner-*.deb
```

### 从源码构建

**环境要求**：Node.js ≥ 20.19 / ≥ 22.12

```bash
git clone <repo>
cd tplanner

# 安装依赖（跳过 Electron 二进制，单独用镜像下载）
ELECTRON_SKIP_BINARY_DOWNLOAD=1 npm install
ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/" npm install --save-dev electron

# 开发模式
npm run dev

# 生产构建（Windows）
npm run package

# 生产构建（Linux — AppImage + deb）
npm run package:linux
```

---

## 局域网同步服务器（树莓派 / Linux）

适用于树莓派 3B 及以上，内存占用约 46 MB。

```bash
# 复制到目标设备
scp -r sync-server/ pi@192.168.x.x:~/tplanner-sync

# 一键安装并配置 systemd 自启动
ssh pi@192.168.x.x
sudo bash ~/tplanner-sync/install.sh
```

安装后：
- 服务默认端口：`37401`（HTTP 同步）、`37402`（UDP 发现）
- 开机自动启动，崩溃自动重启

```bash
# 查看状态
sudo systemctl status tplanner-sync

# 实时日志
sudo journalctl -u tplanner-sync -f

# 健康检查
curl http://localhost:37401/health
```

客户端在**局域网同步**面板点击「扫描局域网」即可自动发现。

---

## 数据存储

| 数据 | 存储位置 |
|------|---------|
| 事件数据库 | RxDB / IndexedDB（浏览器标准存储） |
| 事件缓存 | `userData/events-cache.json`（供 Widget 离线访问） |
| 随手记 | `userData/journals.json` |
| 窗口状态 | `userData/window-state.json` |
| 主题 | `userData/themes/` |
| 局域网配置 | `userData/lan-sync.json` |

> `userData` 路径：Windows `%APPDATA%\tPlanner`，Linux `~/.config/tPlanner`

---

## 快捷键

| 按键 | 功能 |
|------|------|
| `F12` | 打开 / 关闭调试面板 |
| 点击日期列 | 打开当天随笔编辑框 |
| 拖拽事件块 | 移动事件时间 |

---

## 技术栈

| 层 | 技术 |
|----|------|
| 前端框架 | React 19 + Vite 8 |
| 桌面壳 | Electron 41 |
| 本地数据库 | RxDB + Dexie (IndexedDB) |
| UI 组件 | MUI v7 + Tailwind CSS v4 |
| 日期处理 | date-fns + date-fns-tz |
| 国际化 | i18next + react-i18next |
| 构建打包 | electron-builder |
| 同步服务器 | Node.js 内置模块（零依赖） |

---

## 项目结构

```
tplanner/
├── electron/               Electron 主进程
│   ├── main.js             主进程逻辑（窗口、托盘、IPC、通知）
│   ├── preload.js          主窗口 contextBridge
│   ├── widget.html/js      今日便签独立窗口
│   └── widget-preload.js   便签 contextBridge
├── src/
│   ├── components/         React 组件
│   │   ├── Timeline.jsx    时间轴主体
│   │   ├── EventRow.jsx    单日行（含随笔弹窗）
│   │   ├── EventBlock.jsx  单个事件块
│   │   ├── AddEventModal.jsx  新建/编辑事件
│   │   ├── OverdueBanner.jsx  逾期+倒计时横幅
│   │   ├── ClashBanner.jsx    冲突横幅
│   │   ├── ReminderBanner.jsx 提醒横幅
│   │   ├── LanSync.jsx     局域网同步 UI
│   │   └── DebugPanel.jsx  调试面板（F12）
│   ├── database/           RxDB 初始化与 Schema
│   ├── utils/              工具函数
│   └── locales/            i18n 语言包（zh / en）
├── sync-server/            树莓派同步服务器
│   ├── server.js           HTTP + UDP 服务（零依赖）
│   ├── install.sh          一键部署脚本
│   └── tplanner-sync.service  systemd 模板
└── themes/                 内置主题包
```

---

## License

Private — 保留所有权利
