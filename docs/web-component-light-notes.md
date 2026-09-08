# Web 业务组件浅色迁移记录

日期：2026-09-08。范围：`TaskUnit.jsx`、`EventBlock.jsx`、`EventRow.jsx`、`NoteEditor.jsx`、`AddEventModal.jsx`、`EventDetailsModal.jsx`。本文件用于并行协调；全局 CSS 由主 agent 维护，主题和分类 API 由主题适配 agent 维护。

## 统一输入与现有界面

先读取根仓库 `design-assets/tokens/README.md`、`docs/token-migration.md`、`docs/web-desktop-skin-coordination.md`，遵循 `111b90d` 的类别 ID、语义颜色和平台 profile。未修改 canonical JSON 或生成器。

检查了当前组件流程：`EventRow` 负责跨日、分列、状态条和拖动位置；`EventBlock` 包裹共享 `TaskUnit`，后者复用 `TaskCheckbox` 与 `TaskProgress`；创建弹窗采用 MUI，详情弹窗采用现有 `.modal-*` 样式。NoteEditor 原有行内切换、全屏分栏、Markdown checkbox 与提交边界；日记弹层重复 Markdown 展示。

## 本批变更

- 事件与状态条按 `categoryForId(colorId)` 取得明确的浅色背景、前景和边框；分类色点仍采用类别 accent，ID 与顺序保持 0..7。选中、冲突、完成与 shadow 独立按状态表达。
- `EventBlock` 用 `event.surfaceFor/foregroundFor/borderFor`，向子组件传 `--event-foreground`。完成与 shadow 不降低整行透明度，拖动原块隐藏仍保留；拖动预览使用完整可读前景与焦点虚线。
- 详情清单复用 `TaskCheckbox` / `TaskProgress`，保留点击行、勾选子任务及自动更新父任务完成状态的功能。两个共享控件新增可选 `className`，供常规表单和紧凑时间轴使用各自密度。
- 提取 `MarkdownPreview`，用于便签的行内/全屏预览和日记弹层。占位文案采用 React 文本节点；编辑、只读和提交流程保持。便签预览支持键盘 Enter/Space 进入编辑，图标按钮补充可访问名称。
- 分类选择由纯点击 div 改为具备键盘操作、`aria-pressed` 和勾选标记的按钮，使用分类配对。MUI 日期/时间与重复选项在窄屏改为纵向排列。移除废弃 `ColorButton` 和 Tailwind 分类颜色样式。
- 六个业务文件不再直接写入暗色颜色或旧字体选择；MUI 字体消费主题，普通内容 CSS 消费当前 Web/Desktop profile。

## CSS 接口（主 agent 实现）

| 选择器 | 需要的样式职责 |
| --- | --- |
| `.tplanner-task-unit__title` / `__time` | 从 `--event-foreground` 取当前分类或完成前景；保留 15px 标题、10px 时间和 27px 摘要几何 |
| `.event-row-date-month` | block、meta 字号、普通字重、次要文字；月信息保持位于日期下方 |
| `.event-row-journal-dot` | 5px 装饰点、圆形、accent 填充、居中 |
| `.journal-popup` | raised 表面、dialog 边界/圆角/阴影、accent 顶部线；组件仍持有 left/top/zIndex/width 和 flex 几何 |
| `.journal-popup__header` | meta 字号、次要文字、padding 8px 12px、底部分隔线 |
| `.journal-popup__preview` | body profile、padding 12px、min-height 80px、max-height 200px、overflow-y auto |
| `.journal-placeholder` | textMuted，无整体 opacity |
| `.timeline-status-block` | small 圆角、1px 类别边界/3px 左边界，box-sizing border-box；组件继续持有 16px 高度、位置、padding 与颜色 |
| `.timeline-status-title` | 旧紧凑 9px 字号，系统字体、font-weight 600、继承分类前景；不要改状态行高度 |
| `.note-editor` | position relative |
| `.note-editor__preview` / `__textarea` | min-height 90px、max-height 240px、overflow auto；body profile；input 表面、borderControl、control 圆角；padding 12px 52px 12px 16px，给展开按钮留位 |
| `.note-editor--readonly .note-editor__preview` | 普通阅读光标，右 padding 16px |
| `.note-editor__textarea` | display block、width 100%、resize none、系统字体、focus 光标和清晰 focus-visible 边界 |
| `.note-editor__expand` | absolute top/right 8px、profile 控件尺寸、accentText、hoverBackground、borderControl、control 圆角；清晰键盘焦点 |
| `.note-editor__fullscreen` | fixed inset 0、z-index 99999、flex column、canvas 表面与系统字体 |
| `.note-editor__header` | flex、min-height 44px、padding 8px 16px、gap 12px、surface 与底部分隔线 |
| `.note-editor__label` / `__meta` | meta profile，分别 accentText / textSecondary |
| `.note-editor__close` | profile 控件尺寸与 touch target，次要文字、清晰焦点 |
| `.note-editor__body` | flex 1、min-height 0、display flex、overflow hidden；窄屏改列布局 |
| `.note-editor__source` / `__rendered` | flex 1、min-width/height 0、overflow auto、padding 24px、body profile；source input 表面和 focus 光标，分栏 borderSubtle |
| `.event-category-picker` | flex wrap、gap 8px |
| `.event-category-option` | profile 控件尺寸、flex 居中、border 1px solid、control 圆角；`aria-pressed=true` 用 focus outline，保留组件设置的类别配对 |
| `.event-category-option__swatch` | 12–16px 类别 accent 装饰色点 |
| `.modal-timezone-label` / `-time` / `.modal-delete-confirmation` | meta profile、次要/主文字；时间 tabular-nums |
| `.modal-timezone-badge` | meta profile、accentText/currentBackground 配对、小圆角和明确边界 |
| `.modal-checklist-progress` | meta profile、保持可读状态色；覆盖 TaskProgress 的紧凑时间轴字号 |
| `.modal-checklist-checkbox` | 在详情使用普通控件高度/命中目标；不改变时间轴 `.tplanner-task-checkbox` 的几何 |
| `.modal-checklist-text` / `--completed` | body profile；完成用 textMuted 和 line-through，整行 opacity 1 |

## 冲突与未定义项

- **WD-01 保留**：时间轴 27px 摘要、34px 最小事件、16px 状态条、2px 间隔参与叠放算法。本批不修改时间计算、分列、跨日截断、拖动位置和上述高度。10px 时间、9px 状态标题与新 meta=12px 的冲突需要后续成组布局迁移及重叠验证；不通过单纯放大 CSS 字号处理。
- **分类无文案令牌**：颜色选择当前名称使用本地化“颜色 1…8”，并保持 ID 0..7；分类名称翻译（蓝/金/玫瑰等）尚未定义，共享文案后续协调，本批不在组件私建跨端名称映射。
- **WD-02 保留**：业务事件消费 canonical 分类配对，旧 `.tptheme` 只改 accent 无法构造可读完整配对；运行时主题兼容需由主题层统一决策，不能由业务组件恢复透明混色。

## 验证

- 已静态检查六个文件：无直接十六进制/rgba 暗色、无旧内联字号或字体；opacity 仅保留主题状态 API、拖动原块隐藏和预览显式 1。
- 六个组件经 TypeScript JSX 转译诊断，0 个语法错误；`git diff --check` 通过。
- 时间计算、`assignOverlapGroupLanes` / `computeCascadeLayout` 调用与 `laneLayout.js` 未改动。
- 主题 API 与 CSS 合并后，由主 agent 统一进行构建和浏览器组合验证；此记录不将待执行的渲染验收写为已通过。
