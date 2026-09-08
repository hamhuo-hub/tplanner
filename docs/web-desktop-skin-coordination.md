# Web / Desktop 浅色迁移协调

更新：2026-09-08。Web / Desktop 本批实现及构建已完成，并提交、推送至 `origin/master`：`1c7c5441c85c49f249024a6dcdce654cce7cb7a2`。执行工作树：`C:/Users/hamhuo/tplanner/worktrees/master`，起点 `4996f55`，提交后工作区干净。Mobile / Wear 实现 `9417acd` 已推送至 `origin/mobile_andorid`。

完整覆盖、验收证据、9 张截图和复现入口见 [Web / Desktop 换肤验收](https://github.com/hamhuo-hub/tplanner/blob/1c7c5441c85c49f249024a6dcdce654cce7cb7a2/docs/web-desktop-light.md)。

## 所有权与统一输入

- 本任务只负责 Web、Electron 主窗口、Today / Notes 小窗及对应验证；根仓仅维护本协调文档。
- 统一输入：提交 `111b90d` 的 `design-assets/tokens/tplanner-light.tokens.json` 及生成文件、MUI adapter；阅读 `design-assets/tokens/README.md` 和 `docs/token-migration.md` 后实施。
- 不修改 Android / Wear、Hop painter、根令牌源或原生成器。并行任务如调整令牌，请在本文件或跨端迁移说明登记变更，不直接修改 Web 的生成副本。
- Web 工作树已保存可追溯的令牌包副本，使用显式同步脚本从 Git 提交读取并校验 SHA-256；运行与打包不跨工作树读取资产，不手改生成副本。
- JSON SHA-256 为 `3c3f45d1a715d2b8ff146bcaad37d5cf6a7b35787d5b12e67d1d3f86bbd231fb`。已与 Mobile / Wear 负责人确认一致；对方显式 `--android` 生成方式不改变原 5 份导出或默认行为，详见 [android-light-migration.md](android-light-migration.md)。

## 文件分工

| 负责人 | 范围 |
| --- | --- |
| 主题适配 agent | Web 令牌包、同步脚本、`src/design-system/tokens.js` / `index.js`、`src/theme.js`、`src/main.tsx`、`index.html` |
| Desktop agent | `electron/` 两个小窗及共用样式 / 控件、`vite.config.ts` 的资源打包、离线小窗预览 |
| 业务组件 agent | `TaskUnit.jsx`、`EventBlock.jsx`、`EventRow.jsx`、`NoteEditor.jsx`、Add / Details 对话框 |
| 主 agent | 全局与共用 CSS、LoginScreen、其余业务入口、Electron main.js 启动背景、浏览器验收、构建、截图及协调文档 |

修改他人范围前先协调。各 agent 不提交、不覆盖其他改动。Android / Wear 等外部并行任务独立维护自己的文件。

## 冲突 / 未定义项

| 编号 | 项目 | 当前处理 |
| --- | --- | --- |
| WD-01 | 时间轴 27px 摘要 / 34px 最小事件 / 16px 状态行是布局算法输入，与新版 12px meta 和 44px 任务行存在冲突 | 本批保留原时间几何及 10px 紧凑时间文字；后续成组迁移字号、排布和重叠算法。常规表单、小窗使用平台尺寸 |
| WD-02 | `.tptheme` 自定义主题历史接口不保证 CSS / MUI / 独立小窗一致 | 本批明确统一浅色，不声明完整运行时主题切换；未来统一消费方式后再迁移旧接口 |
| WD-03 | 源令牌没有旧界面所有装饰渐变和透明叠色的逐项定义 | 优先归并已有 surface / state / category 语义；必要新语义登记后跨端协调，不私建颜色 |
| WD-04 | 分类 `colorId` 与类型 / 交互状态混用风险，以及旧命名差异 | 保留 0..7 ID 和顺序，分类使用 foreground/background 配对；交互状态独立表达。跨端分类命名留待统一 |
| WD-05 | 缺少独立 scrim 令牌 | MUI 与自定义弹层共用 dialog.shadowColor 的 32% 合成遮罩；源包明确新角色后同步替换 |
| WD-06 | textMuted / completedForeground 配 disabledBackground 约 4.405:1 | 完成 / 已过正文使用普通 surface，整行 opacity=1，实测约 5.43:1；已与 Mobile / Wear 同步该结论 |
| WD-D01 | 小窗 240px 最小宽度与 Desktop 24px pageInset、三个标题控件冲突 | 外壳使用已有 spacing.block=12、正文 spacing.inline=8；标题与正文允许换行 |
| WD-D02 | Electron 原生 shadow 无法直接使用 CSS blur / opacity | 保留 hasShadow，CSS 消费边沿和圆角；原生拖动、置顶、透明边角和重开仍需可见窗口验收 |
| WD-T01 | 原 MUI adapter 未覆盖全部实际控件，picker 聚焦 selector 优先级更高 | 应用层补全 picker / menu / Switch 等并验证实际 focus 深橙色；是否上移共用 adapter 由源包任务协调 |

## 进度与验证

- 已检查登录、主窗口、时间轴、创建 / 详情、便签 / 日记和两个小窗；复用 TaskCheckbox / TaskProgress / MarkdownPreview，抽取共用面板、输入、菜单和小窗控件。
- `npm run build`、7 文件源包校验、`git diff --check` 通过；源包 393 个令牌、54 组必需对比检查通过。
- 浏览器验证分类配对、完成态、表单聚焦、子项完成 / 阻塞、Markdown 全屏与保存，以及窄屏适配；分类标题对比最低约 5.05:1，完成态约 5.43:1。
- 最终覆盖复查未发现遗漏的暗色业务页面。11 个桌面复制资产与源一致、12 条本地引用完整；生产产物不含验收入口或 fixture。
- 真实 Electron 隐藏窗口加载打包后小窗资源通过；原生可见窗口操作与真实 IPC / 生产同步链路不属于离线 fixture 的通过声明。
