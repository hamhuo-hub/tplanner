# Desktop Today / Notes 浅色接入

日期：2026-09-08。统一输入为根仓库提交 `111b90d`，消费本工作树 `design-assets/tokens/generated/tplanner-light.css` 与 `tplanner-light.mjs`，源包与 SHA-256 校验由主题适配层维护。本批不修改根令牌源、Electron IPC 或真实用户数据。

## 现有界面与复用

检查了 `electron/widget.html` / `widget.js`、`notes-widget.html` / `notes-widget.js`、两个 preload 和 `electron/main.js` 的独立窗口加载入口。原有小窗各自复制暗色色板、24px 标题按钮、10px 窗口圆角和 pin/open/close 事件；Today 另外复制了分类数组，past 使用整行 `.45` 透明度，Notes 使用多处硬编码 Markdown 配色。

- `shared-widget.css` 现在负责窗口表面、系统字体、标题/底栏、拖动区、36px 鼠标控件、44px 触屏命中目标、焦点、选中文字、滚动条及减少动态效果。
- `widget-shared.mjs` 复用 pin/open/close 控件逻辑，并从生成值读取稳定的 `id0..id7` 分类。分类未知值回落至 0，持久化数据不改写。
- `widget.css` 负责任务行、可复用 checkbox 视觉、子项、进度和状态标签。`notes-widget.css` 负责输入边界、光标、Markdown 标题/正文/代码/引用/列表/表格。

## 接入后的规则

两个 HTML 都显式声明 `data-tp-theme="light"`，按 16px 根字号消费 rem；正文和任务标题为 Desktop 15px，辅助信息 12px。标题和长备注允许换行，内容滚动区可自然增高。窗口使用 panel 12px 圆角和白色亮沿；原生阴影仍由 `BrowserWindow.hasShadow` 提供，没有为每个任务叠加阴影。

分类填充和标题使用对应的 `background/foreground` 配对，保留原分类标记。进行中使用明确边框和“现在”标签，即将使用 warning 语义。完成优先于时间范围，已完成条目不会同时显示“现在”。已过/完成使用 surface 底，整行 opacity=1；完成以划线、勾选和“完成”标签表达。未完成子项继续阻止父任务完成，禁用边界不依赖降低透明度。

任务勾选、子项和完成分组/进度展开均使用原生按钮，可以通过键盘操作；勾选状态有 `aria-checked`，展开状态有 `aria-expanded`。两个小窗保留 pin、打开主窗口、关闭/隐藏行为。Today 去掉远程 Google 图标字体，加载/空状态使用本地字符；没有新增网络依赖。

## 构建与验证

`vite.config.ts` 的 `copyWidgetAssets` 在开发开始和生产完成时复制两个 HTML、三个 CSS、两个 JS、共用 MJS、marked，以及准确的生成 CSS/MJS 到 `dist-electron/`。文件缺失会令构建失败，不再静默漏拷。独立文档的资源和 module import 都只解析该目录；生产 renderer 不跨工作树读取令牌。

已验证：

- `node --check`：两个 renderer JS、共用 MJS、离线预览服务器通过。
- 调用实际 Vite copy 插件后，两个文档和共用模块的全部资源路径存在且为本地引用。
- 三个手写 CSS 使用的每个 `--tp-*` 变量都在生成 CSS 中定义；分类 0..7 稳定，非法 ID 回落通过。
- 隔离数据的 Electron 隐藏窗口通过 `file://` 实际加载：8 类、完成/已过 opacity=1、完成状态优先、父项阻塞、15px 正文、240px Today 无横溢；Notes Markdown 主文与代码颜色符合令牌。
- 隐藏窗口不能等价模拟可见窗口焦点；输入焦点、原生拖动/透明边角、重新打开和真实窗口视觉由主任务继续检查。完整应用构建由主任务执行。

## 离线界面预览

运行 `node scripts/widget-review-server.mjs`，仅监听 `127.0.0.1:4175`：

- Today：`http://127.0.0.1:4175/widget.html`
- Notes：`http://127.0.0.1:4175/notes-widget.html`

服务器读取实际 `dist-electron` 产物，保留 CSP 并注入同源 fixture。固定为当日 14:00，覆盖 now/soon/past/completed/blocked/8 类别、长中文和 Markdown。事件、日记和 localStorage 均只保存在页面内存；刷新复原。pin 仅切换样例状态，open/close 没有真实窗口作用，不连接 IPC、用户存储或同步。文件请求使用白名单，只提供明确列出的 renderer 资产，路径遍历返回 404。该服务器不加入生产启动或打包流程。

## 协调记录

| 编号 | 冲突或范围限制 | 本批决定 |
| --- | --- | --- |
| WD-02 | `.tptheme` 安装和 IPC 目前只发往主窗口，两个小窗没有完整运行时主题切换入口 | 独立小窗固定为统一浅色，不新增旧主题覆盖；未来运行时主题必须统一 CSS、MUI 和独立页面，再解除基线限制 |
| WD-D01 | 小窗原始默认/最小宽度较窄，与 Desktop pageInset=24 和三个 36px 标题控件同时出现冲突 | 外壳使用现有 spacing.block=12、正文 spacing.inline=8；保留原生最小窗口配置，让标题/正文自然换行；不新增私有尺寸令牌 |
| WD-D02 | textMuted/completedForeground 与 disabledBackground 组合约 4.405:1 | 完成和已过使用 surface 底；disabledBackground 只用于无正文的禁用 checkbox 标记，整行文字保持可读 |
| WD-D03 | Electron 原生窗口 shadow 没有可直接表达令牌 blur/opacity 的现有 API | 保留平台 `hasShadow`，CSS 消费 panel edge/radius；不声称原生阴影与 CSS 阴影数值相同 |

全端公共协调入口：根仓库 `docs/web-desktop-skin-coordination.md`。桌面小窗的 44px 任务行不参与 Web 时间轴 27px 摘要叠放算法。
