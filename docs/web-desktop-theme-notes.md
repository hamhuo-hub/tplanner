# Web / Desktop 主题适配协调记录

日期：2026-09-08。范围：主窗口主题适配层和本地统一令牌消费包；业务页面及独立小窗由并行负责人实施。

## 统一来源与接口

- 来源提交：`111b90d`，完整提交与 7 个消费文件 SHA-256 见 `design-assets/tokens/provenance.json`。同步从显式 `--source-root` 的 Git 提交读取，运行时只消费本工作树资产；哈希统一为 UTF-8 / LF，允许 Windows 的 Git 文本换行转换。
- `src/design-system/tokens.js` / `index.js` 导出 `lightTokens`、`categoryTokens`、`categoryForId`、`colors`、`event`、`semantic`、`typography`、`geometry`、`timeline`、`platformProfile` 和 `installDesignTokens`。
- `colors` 包含所有 `semantic.color` 角色；旧 `gold/goldBright/goldDark` 是 `accentText` 的兼容别名。橙色填充明确使用 `accent/accentHover/accentPressed`，填充前景使用 `onAccent`。
- `categoryForId` 保留整数 0..7；无效值回退 id0。`event.surfaceFor/borderFor/foregroundFor` 接受分类对象、ID 或旧 accent 字符串，返回源令牌配对，不再计算深色混色。
- 主窗口明确 `data-tp-theme="light"`；存在 preload 的 `window.electronAPI` 时选择 desktop profile，否则 web。`--tp-profile-{body,heading,title,task-title,meta}-{font-size,line-height,font-weight}` 以及 control / touch / task-row / page-padding 变量供现有 CSS 使用。
- `src/theme.js` 在原样同步的 `createLightThemeOptions(platform)` 上补全对话框内容、日期时间选择器、菜单、提示、IconButton、ToggleButton、Switch 等实际组件；不维护第二套颜色。移动 Web 输入至少 16px，coarse pointer 使用平台触摸目标。
- MUI Backdrop 使用共享 `--tp-overlay-background`（dialog shadowColor 的 32% 合成，主协调 WD-05 跟踪缺少专门 scrim 令牌）。全天 Switch 的未选中 thumb 使用 textSecondary，选中 thumb 使用 accentText，track 使用 disabledBackground / selectedBackground 并保留 borderControl 边界；未选中/选中 thumb 对 track 为 5.58:1 / 5.42:1，track 边界对底色最低 3.02:1。
- MUI X picker 的默认颜色 variant 使用 `.Mui-focused:not(.Mui-error)`，比 canonical adapter 的普通 focused selector 优先级高。应用层用 `&&.Mui-focused:not(.Mui-error)` 显式覆盖 picker / 普通 outlined input / InputLabel，焦点使用 focus 深色；错误态独立保持 error，不污染分类或填充色。

## 冲突与未定义处理

| 编号 | 发现 | 本批处理 / 后续协调 |
| --- | --- | --- |
| WD-T01 | 令牌 meta 12px、任务行 44px 与现有时间轴 27px 摘要算法冲突 | 时间轴保留 15px 标题 + 2px 间隔 + 10px 时间，最小事件 34px、状态行 16px；放大紧凑标签必须和时间轴几何一起迁移。其余界面消费平台排版。 |
| WD-T02 | `completedForeground` / `textMuted` 放 `disabledBackground` 的实际对比只有 4.405:1，已完成正文不能按禁用控件处理 | completed / shadow 使用 `component.task.normalBackground` + `completedForeground`，整行 opacity=1、无去饱和滤镜；复用现有任务表面，无新增令牌。 |
| WD-T03 | 旧 `gold` 同时承担文字、填充、边界 | 兼容别名优先保证浅底文字可读；已同步其他负责人，按钮填充逐点改为 `accent`，分类填充用成对的 category 角色。 |
| WD-T04 | 原 MUI adapter 未覆盖所有业务 picker/menu/tooltip/switch 样式 | 应用主题层补全，全部引用 canonical 值；若成为其他 MUI 客户端共同需求，后续可协调上移到 canonical adapter。 |
| WD-T05 | `.tptheme` 文件与 IPC 历史接口不代表 CSS、静态 MUI 和独立窗口支持同一套运行时主题切换 | 本批只提供明确浅色基线，不声明运行时换主题支持；业务主题导入交互由主负责人核验。 |

## 已完成的适配层检查

- `node scripts/sync-light-tokens.mjs --check --source-root C:/Users/hamhuo/tplanner`：7 个消费文件与指定 canonical 提交一致。
- `npx tsc -b`：通过（2026-09-08，适配层落地后）。
- Node 实际导入验证：8 个分类 ID 顺序、旧强调字别名、event 分类配对、completed/shadow 的 opacity=1、27/34/16 时间轴几何、web 40px / desktop 36px 控件 profile 及明确 light 标记均通过。
- 组合对比检查：正常 / 选中分类的最小正文对比 5.05:1；完成 / shadow 为 5.43:1，accentText / canvas 为 5.26:1。
- 页面截图、全构建和业务交互验收由主负责人汇总；本记录不等同于所有界面均已验收。
