# 浅色设计令牌：跨端迁移说明

## 交付范围

用户已确认 Web、Desktop、手机和 Wear 应用采用统一的浅色方向：冷灰底、白色表面、深色文字、橙色强调。新的令牌包是后续统一开发的基线，**不代表所有客户端界面已经迁移完成**。当前生产界面的黑金主题仍属于 legacy；本说明只记录已核实的入口、旧值到新语义的映射，以及实施顺序。

令牌源为 `design-assets/tokens/tplanner-light.tokens.json`，生成脚本为 `scripts/generate-design-tokens.py`。消费文件位于 `design-assets/tokens/generated/`：

- `tplanner-light.css`：Web、Desktop 主窗口和独立 HTML 小窗的 CSS 适配输入。
- `tplanner-light.ts`：React/MUI 等 JavaScript 使用方的类型化令牌输入。
- `TPlannerLightTokens.kt`：Android 手机和 Wear 的待接入 Kotlin 产物；暂放在设计资产目录，不编入生产源集。
- `contrast-report.json`：生成时的颜色配对检查结果，不等同于整页、所有交互状态的视觉验收。

修改源 JSON 后重新生成；不要在各端复制一份颜色再维护，也不要直接修改生成文件。以下目标名称指 JSON 中的语义路径，CSS/TypeScript/Kotlin 的实际导出名称以生成文件为准。

Android 正式接入仍需保持 `shared/src/main/kotlin/com/hamhuo/tplanner/designsystem/TPlannerDesignTokens.kt` 为唯一生产 ARGB 定义入口。迁移时将生成对象并入该 canonical 文件，或同时调整唯一生成入口与检查规则，使该文件成为唯一的生成消费入口；不能直接在生产源码下再添加一份独立颜色对象。当前设计资产中的 Kotlin 文件只用于开发对照与后续接入。

## 核实依据与路径约定

Android 当前仓库根目录包含 `app/`、`wear/`、`shared/`。Web/Desktop 的实际 Git 工作树是根目录下的 `worktrees/master/`，核实时分支为 `master`、HEAD 为 `44e4126`，状态干净。旧的 `Documents/GitHub/tplanner-master` 路径不存在，不能作为迁移依据。检查未发现适用的 `AGENTS.md`。

本文件中的 Android 路径相对于当前仓库根；标记为 Web/Desktop 的路径相对于 `worktrees/master/` 对应仓库根。文档不依赖这两个工作树之间的运行时相对导入。后续通过发布或同步设计包接入各自仓库；本次不修改其他工作树。

## 已核实的现状

### Web/Desktop 主窗口

`src/design-system/tokens.js` 已有基础色、语义状态、事件组件和时间轴布局四层；`src/main.tsx` 调用 `installDesignTokens()` 安装 CSS 变量，`src/theme.js` 则用同一模块的 JavaScript 值创建 MUI 主题。

| 现有项目 | 已核实值或行为 | 入口 |
| --- | --- | --- |
| 主要表面 | 背景 `#0E0E0E`，表面 `#1A1A1A`，浮层 `#222222`，控件 `#252525` | `src/design-system/tokens.js` |
| 文本、边框 | 主文 `#E0D8C8`，次文 `#7A7163`，禁用 `#3A342A`；边框 `#2D2D2D` / `#383838` | `src/design-system/tokens.js` |
| 强调与状态 | 金色 `#C9A84C`，亮金 `#F0C040`，暗金 `#6B5928`；红 `#C0392B`，绿 `#4A7C59`，蓝 `#5B8FCC`，青 `#4A9DA8` | `src/design-system/tokens.js` |
| 字体 | display 为 Oswald，body/mono 为 IBM Plex Mono；全局 13px、行高 1.5；任务标题 15px，时间 10px，徽标 10px | `src/design-system/tokens.js`、`src/index.css` |
| 圆角 | JS small 2px、medium 9px；CSS small/default/large 分别为 2/3/4px | `src/design-system/tokens.js`、`src/index.css` |
| 常用动效 | 120ms ease、200ms ease；冲突/今日脉冲 2s，toast 250ms | `src/index.css` |
| 任务结构 | 任务用勾选框，其他条目用色条；完成划线；未完成子项会禁用父任务勾选 | `src/design-system/TaskUnit.jsx` |
| 事件状态 | selected/normal/completed 表面分类色混入量为 82%/65%/30%；opacity normal/selected/shadow/completed 为 1/1/.25/.45 | `src/design-system/tokens.js`、`src/components/EventBlock.jsx` |
| 类型与时间轴 | 实际使用 event、task、status、reminder；status 有独立条带，完成任务以背景 shadow 显示 | `src/components/EventRow.jsx`；`src/utils/constants.js` 的旧枚举未列出 reminder |

主窗口还有独立样式入口，不能只替换 `tokens.js`：

- `src/components/LoginScreen.css` 使用浅奶白 `#EEEDE9`、深色 `#171717` 和系统字体，未引用主窗口的同一套语义。
- `src/components/NoteEditor.jsx` 直接硬编码旧文字、金色、背景及边框。
- `src/components/EventRow.jsx` 的日记浮层仍硬编码深色表面；时间轴小时网格、拖动区域也有直接写入的金色透明值。
- `src/theme.js` 固定 `palette.mode: 'dark'`，并在创建时捕获 JS 颜色。CSS 变量变化不会自动改变这些值。

### Desktop 独立小窗

`electron/widget.html` 与 `electron/notes-widget.html` 是独立页面，不经过 React 根节点或 MUI ThemeProvider。它们各自定义 `--bg`、`--surface`、`--gold`、`--text` 等变量，采用 Segoe UI / Microsoft YaHei 13px、窗口圆角 10px。`electron/widget.js` 还复制了一份分类色数组。

Today 小窗的 now、past、done 是状态：now 使用强调背景和左边线，past 使用 `.45` 透明度，done 使用勾选与划线。这些状态不能重新解释为条目类别。Notes 小窗的 Markdown 标题、代码块、光标及滚动条也需要一起适配。

### Android 手机与 Wear

主要共享入口是 `shared/src/main/kotlin/com/hamhuo/tplanner/designsystem/TPlannerDesignTokens.kt`。其中 `TPlannerColors` 仍是 legacy 黑金产品 UI；`TPlannerWatchFacePalette.Hop` 已独立保存参考图校准后的冷灰、亮沿、墨色和橙色。

| 使用方 | 实际入口 | 现状 |
| --- | --- | --- |
| 手机颜色别名 | `app/src/main/java/com/hamhuo/tplanner/ui/Theme.kt` | `BG`、`SURFACE`、`GOLD`、`DIM` 等包装共享颜色为 Compose Color |
| Wear 控件别名 | `wear/src/main/kotlin/com/hamhuo/tplanner/ui/WearUiStyle.kt` | `WEAR_BG`、`WEAR_GOLD`、`WEAR_DIM` 等直接引用共享颜色；字体为平台 sans-serif regular/medium/bold |
| 共享任务与反馈 | `shared/src/main/kotlin/com/hamhuo/tplanner/designsystem/TPlannerTaskUnitView.kt`、`TPlannerSyncFeedbackView.kt` | 任务标题、时间、进度、完成状态和同步消息依赖颜色语义；部分位置仍通过 Gold/GoldDark/Teal 表达不同含义 |
| 手机时间轴 | `app/src/main/java/com/hamhuo/tplanner/timeline/components/TimelineItemCard.kt`、`TimelineStatusStrip.kt` | 按 `colorId` 填充卡片/条带，存在白色及带透明度白色文本，浅色迁移需要显式的分类前景适配 |
| Hop 表盘 | `wear/src/main/kotlin/com/hamhuo/tplanner/watchface/HopFacePainter.kt`、`HopFaceMetrics.kt`、`HopTimeline.kt` | 颜色、几何和时间布局已有独立职责；表盘的玻璃亮沿、凹陷阴影属于 watch profile |

Android 的现有字号和几何是 legacy 使用事实，不是新的统一规范。手机任务标题 15sp、Wear 17sp；手机主体 16sp、Wear 15sp；时间/进度使用 monospace。共享任务行手机最小高 38dp、内边距 14×5dp，Wear 最小高 58dp、内边距 13×9dp。新的平台入口为 `platform.web.typography` / `platform.web.geometry`、`platform.desktop.typography` / `platform.desktop.geometry`、`platform.phone.typography` / `platform.phone.geometry`、`platform.wear.typography` / `platform.wear.geometry`，用于适配排版、控件高度、命中尺寸和页面/行内边距。

共用圆角由 `semantic.radius.small`、`semantic.radius.control`、`semantic.radius.card`、`semantic.radius.dialog`、`semantic.radius.pill` 管理，不能各平台再次建立相互独立的圆角色板。平台 profile 负责适配尺寸和输入方式；旧的零散圆角是待迁移事实，不是继续保留的规范。

手机时间轴存在 6.5–11sp 的紧凑字号；应作为可读性复核项，不能直接推广为新的推荐正文规范。Hop 的数字字体也不能自动推广到输入框或长文本。

## 新语义与旧入口的映射

下面是拟实施的适配映射，不表示这些生产调用点已经替换。

| 新语义路径 | 责任 | Web/Desktop 旧入口 | Android 旧入口与判断 |
| --- | --- | --- | --- |
| `semantic.color.canvas` | 应用画布底色 | `colors.background`、`--clr-bg`、小窗 `--bg` | `Background`、`BG`、`WEAR_BG` |
| `semantic.color.surface` | 常规内容表面 | `colors.surface`、`--clr-surface` | `Surface`；`SurfaceLow` 按实际层级归并 |
| `semantic.color.raised` | 浮层、抬高的内容表面 | `surfaceRaised`、`--clr-raised` | `SurfaceRaised` / `SURFACE2` |
| `semantic.color.input` | 输入区表面 | MUI input 及局部控件背景 | `InputSurface` / `INPUT_SURFACE`；Control 不能机械替换，先判断是否输入、按钮或选中表面 |
| `semantic.color.textPrimary` | 主体内容 | `textPrimary`、`--clr-text`、小窗 `--text` | `TextPrimary`、`TextEditor` |
| `semantic.color.textSecondary` | 仍需清晰阅读的辅助内容 | `textSecondary`、`--clr-text-dim` | `TextSecondary` / `DIM` 的时间、说明文字 |
| `semantic.color.textMuted` | 非关键或弱化内容 | `textMuted`、`--clr-text-mute` | `EmptyState`、GoldDark 等按具体文案用途判定；禁用状态也要检查实际底色 |
| `semantic.color.borderSubtle` | 卡片分隔、轻边界 | 普通内容分隔线、部分 `--clr-border` | 卡片和条带的 Border 用法 |
| `semantic.color.borderControl` | 输入和可操作控件边界 | 表单/按钮描边、部分 `--clr-border-bright` | 字段、按钮、checkbox 的 Border 用法 |
| `semantic.color.accent` | 强调色填充与非文本标记 | `gold`、`--clr-gold` 的填充用途 | Gold 的主操作、选中填充、当前时间标记 |
| `semantic.color.accentText` | 浅底上的强调文字 | 链接、文字按钮、强调元数据 | Gold/GoldDark 的文字用途逐个判断，不能使用一个橙色值替换全部 |
| `semantic.color.onAccent` | 强调填充上的内容 | `textOnAccent`、金色按钮上的黑字 | 旧 BG-on-Gold 字样或图标 |
| `semantic.color.focus` | 键盘焦点、选择及拖动轮廓 | focused fieldset、选中 outline | 焦点、选择和拖动边界中的 Gold 用法 |
| `semantic.color.success` | 成功、已完成的反馈 | green、success；小窗完成反馈 | Green，以及同步成功使用的 Teal |
| `semantic.color.error` | 错误、删除、冲突 | red、error、conflict | Red / WEAR_RED |
| `semantic.color.info` | 信息提示 | blue、info | Blue/BlueBright 的信息用途；当前时间应另用 current/强调角色 |
| `semantic.color.warning` | 需要注意但非错误的状态 | MUI warning 当前复用 gold | 即将发生、警告等按产品状态适配，不按原颜色猜测 |
| `semantic.color.currentBackground` | 当前条目的弱强调背景 | `.item.now`、部分 goldGhost/goldSelected | 当前条目使用的 BlueGhost/GoldGhost；其他 hover/pressed 用法应由状态组件处理 |

`accent` 与 `accentText` 必须分开。参考图的橙色适合细线或填充，不能直接假定它作为浅底小字或白字按钮底色时仍有足够对比。焦点、错误、当前状态也不能只靠颜色表达。

旧的 `GoldPressed`、`goldHover`、`goldGhost`、表面混色比例、disabled opacity 等不能按旧色名机械替换。源 JSON 已提供 `semantic.color.accentHover`、`semantic.color.accentPressed`、`semantic.color.hoverBackground`、`semantic.color.selectedBackground`、`semantic.color.disabledBackground`、`semantic.color.disabledForeground`，以及 success/error/info/warning 各自的 Background 配对；应按组件状态选用这些语义，而不是重新计算一套透明度。特别是整行 `.45` 会同时降低标题、时间、按钮和进度的可读性，需要检查组合后的实际结果。

## 分类与状态不可混合

`semantic.category.id0` 至 `semantic.category.id7` 保留稳定的 ID **0..7**：蓝、金、玫瑰、绿、紫、橙、青、灰。每个对象的 `id` 保存原数字。它们表示用户为条目选择的分类颜色，**不是 task/event/status/reminder 四种任务类型的固定配色**。

迁移保留持久化的 `colorId` 及顺序；不得因为主强调色改成橙色而重排、重编号或统一覆盖全部分类。原数组存在于 Web/Desktop `src/design-system/tokens.js`、`electron/widget.js`，以及 Android `TPlannerColors.EventPalette`；以后由同一份分类令牌生成。

每个分类对象包含六个配色角色：`accent` 保留旧颜色，用于分类色点和小标记；`foreground` / `background` 为浅色分类块的文字/背景配对；`border` 为相应边界；`solid` / `onSolid` 为实色块的背景/文字配对。调用点按呈现方式取配对，不能把保留身份用的 `accent` 直接当成所有卡片背景，或把 `onSolid` 用到浅色 `background` 上。

selected、completed、shadow、conflicting、current、soon、disabled 是呈现或交互状态。保留这些维度及已有完成阻塞规则，再通过新语义表达。分类填充与正文前景需要成对适配；不得继续对每一种分类背景统一假设白字。状态条带类型 `status` 与“当前/即将/已完成”状态也须保持区分。

## 建议实施顺序与验收边界

### 1. 共享主题适配层

先把生成 CSS/TS 接入 Web/Desktop 的 `installDesignTokens()` 和 MUI theme factory，同时将 MUI `palette.mode` 切换为明确的浅色。兼容旧 CSS 别名可以减少一次性改动，但静态 JS 颜色、theme overrides、selected outline 和 event text 也必须从相同语义得到值。

Android 先完成生成资产到唯一 canonical ARGB 入口的接入，再通过 `Theme.kt`、`WearUiStyle.kt` 和共享 View 颜色适配层消费。生成资产和生产源码之间不能存在两套可独立修改的颜色定义。本阶段应明确哪些入口已迁移、哪些仍为 legacy；仅生成文件不能算主题已经接入。

源文件中的尺寸使用 logical px：Web/Desktop 几何输出 CSS px，字号按 16px 基准输出 rem；Android 几何数值映射 dp，fontSize 与 letterSpacing 数值映射 sp。原生绘制在最终边界统一做密度/字体缩放换算，不能直接当裸屏幕 px 使用，也不能重复乘密度。Web 现有根字号为 13px，接入 rem 字号时需要明确新的根字号与 MUI 基准，避免无意缩成目标值的 13/16。

### 2. 任务、事件、编辑器和表单

按常规、当前、选中、完成、禁用、冲突、输入焦点、错误等状态检查任务单元和事件块。保留标题、时间、checkbox、子项阻塞与完成交互。再覆盖 NoteEditor、日记浮层、LoginScreen、MUI 对话框和输入框、手机/Wear 创建流程。

对文字强调、填充强调和 onAccent 逐一选语义；检查长中文、长时间文本、空状态、放大字体及键盘焦点。不可把细小的装饰标记尺寸直接视为可点击目标尺寸。

### 3. 时间轴与已验证几何

颜色迁移先保持现有时间计算、冲突分列、跨日截断和拖动定位。Web/Desktop 的摘要识别区固定为 **27px = 15px 标题 + 2px 间距 + 10px 时间**，事件最小高度 34px，状态行高 16px、间距 2px。这个值被叠放算法使用，不能只改字号却不改几何和相应验证。

Web/Desktop 按现存 `src/utils/laneLayout.js`、`src/components/EventBlock.jsx` 和 `src/components/EventRow.jsx` 核验分列、摘要识别区和状态条行为；Android 检查 `app/src/main/java/com/hamhuo/tplanner/timeline/` 的几何、布局与组件使用方。先使用当前仓库实际保留的检查入口，不依赖或恢复已删除的旧测试文件。字号或密度调整应单独验证重叠、很多状态条、长标题、极短事件及日期边界，避免把布局变化混入一次颜色替换。

### 4. Electron Today / Notes 小窗

在两个独立 HTML 页面接入同源生成 CSS，并通过已有构建/打包路径把文件包含进桌面包；不能假设主窗口安装变量会跨页面生效。`electron/widget.js` 的分类数组改为共享输出；更新 now/past/done、子项、Markdown、同步反馈和输入光标的语义。

小窗 CSP 与脚本加载方式独立，需按原有本地资源方式接入，避免新增运行时网络依赖。验收包含透明窗口外形、拖动区、pin/close 等控件、滚动内容和独立窗口重开后的初始主题。

### 5. Hop 组件与 Wear profile

最后让 Hop 渲染器接入 `component.hop.color`、`component.hop.geometry`、`component.hop.typography`；它是表盘组件命名空间，不是 `platform.watch`。其中橙线和当前时间的颜色角色为 `component.hop.color.time`。Hop 的冷灰、橙色可作为共同方向，但玻璃亮沿、凹陷阴影、超大表盘及内容裁剪保留在表盘组件层，不扩散成所有客户端的卡片样式。

Wear 应用的创建、列表和设置页面属于浅色产品 UI；表盘 active/ambient 则有独立渲染状态。迁移令牌不附带改变 ambient 策略、时间几何或任务可见区行为。验证既有表盘截图、不同尺寸、边缘文字和 active/ambient 切换，确保抽取令牌后原生画面仍一致。

## 限制与后续记录

- 本交付是令牌源、平台输出及迁移地图；生产入口尚未接入的部分继续明确标为 legacy，不使用“全端已统一”描述。
- 字体、控件和命中尺寸按四个平台 profile 适配，共用圆角消费 `semantic.radius`。按源文件的 logical px 约定映射 CSS px/rem、Android dp/sp，不把数值直接作为裸屏幕像素，也不强制所有屏幕拥有相同字号。
- `contrast-report.json` 只验证其覆盖的颜色配对；透明度、分类底色、照片/玻璃阴影、disabled 状态和真实页面仍需组合验收。
- 现有主题文件协议及 Electron IPC 不等于所有渲染器已经支持运行时主题切换。适配时以实际消费入口为准，不根据旧注释推断功能已完成。
- 文档列出的局部硬编码、极小字号及反馈布局是后续迁移检查点；本次没有顺带修改业务状态、同步协议、保存的数据或各端交互。
- 每一批迁移应记录已接入文件、保留的 legacy 入口、验证结果和截图，再推进下一批，便于区分“令牌可用”和“界面已迁移”。
