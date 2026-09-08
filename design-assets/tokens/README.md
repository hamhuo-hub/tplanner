# TPlanner 浅色设计令牌

全客户端以 **冷灰蓝底、深色文字、橙色强调、白色边缘** 为统一方向。Web、Desktop、手机与 Wear 共用语义；字体、行高、点击尺寸按平台适配。本包是浅色开发基线，现有业务界面尚未整体换肤。

## 使用入口

| 文件 | 用途 |
| --- | --- |
| [tplanner-light.tokens.json](tplanner-light.tokens.json) | 唯一人工维护的浅色令牌源 |
| [preview.html](preview.html) | 离线色板与各端组件尺寸预览，生成后可直接打开 |
| [generated/tplanner-light.css](generated/tplanner-light.css) | Web、Electron 主窗口及独立小窗 |
| [generated/tplanner-light.ts](generated/tplanner-light.ts) | TypeScript 值与类型推断 |
| [generated/tplanner-light.mjs](generated/tplanner-light.mjs) | 不经过 TypeScript 的 JavaScript 使用方 |
| [generated/TPlannerLightTokens.kt](generated/TPlannerLightTokens.kt) | Android 接入样板，当前未加入生产 source set |
| [adapters/mui-light.ts](adapters/mui-light.ts) | MUI 浅色 theme factory、文字按钮/输入框/焦点等基础适配 |
| [generated/contrast-report.json](generated/contrast-report.json) | 可重复的颜色配对校验及表盘已知差距 |
| [跨端迁移地图](../../docs/token-migration.md) | 实际代码入口、旧语义映射与迁移顺序 |

从仓库根执行；不需要额外 Python 包：

```powershell
python scripts/generate-design-tokens.py
python scripts/generate-design-tokens.py --check
```

只修改源 JSON，再生成。`--check` 检测导出是否过期；别名、类型或必需对比度不通过时生成失败。MUI 适配器只引用生成值，组件层不得再复制颜色。格式使用 [DTCG 2025.10](https://www.designtokens.org/tr/2025.10/format/) 的 `$type` / `$value` 与别名写法；生成器仅支持本包使用的类型和整 token 别名，不是通用 DTCG 实现。

## 颜色语义

| 令牌 | 值 | 使用方式 |
| --- | --- | --- |
| `semantic.color.canvas` | `#E5E8ED` | 应用画布，来自照片盘面 |
| `semantic.color.surface` | `#F6F8FA` | 常规内容表面 |
| `semantic.color.raised` / `input` | `#FFFFFF` | 浮层、输入区 |
| `semantic.color.textPrimary` | `#212021` | 标题、正文，来自照片数字 |
| `semantic.color.textSecondary` | `#55565A` | 辅助说明、时间 |
| `semantic.color.textMuted` | `#606670` | 弱化信息、已完成任务；保持可读 |
| `semantic.color.accent` | `#F77128` | 橙色强调填充、表盘线，来自照片 |
| `semantic.color.accentText` / `focus` | `#A23F0A` | 浅底链接、文字按钮、焦点 |
| `semantic.color.onAccent` | `#212021` | 橙色按钮上的文字 |
| `semantic.color.edgeHighlight` | `#F6F8F2` | 白色装饰亮沿，来自照片 |
| `semantic.color.borderSubtle` | `#C7CBD2` | 非交互分隔线 |
| `semantic.color.borderControl` | `#7C8087` | 输入框、checkbox 等必要边界 |
| `success` / `error` / `info` / `warning` | 各自配套浅底 | 成功、错误、信息、提醒；不借用类别色 |

照片取样坐标和方法见 [Hop 参考说明](../../docs/hop-watchface.md)。照片确认的是盘面、亮沿、墨色与橙线；产品表面、状态、尺寸及深色强调字是本次补全的开发规范，不能混称为照片取样。

业务正文配对以 4.5:1、必要控件边界以 3:1 为本包校验门槛，依据 [WCAG 2.2 文本对比度](https://www.w3.org/TR/WCAG22/#contrast-minimum) 和 [非文本对比度](https://www.w3.org/TR/WCAG22/#non-text-contrast)。这只是颜色配对检查，不代表整页可访问性验收。

取样橙色对盘面约 2.33:1，白字对取样橙色约 2.86:1。橙色主按钮使用深字和深色细轮廓；链接使用 `accentText`。Hop 的橙色时间字保留已确认的表盘视觉，报告中单独列为未达正文门槛的诊断项，不作为业务文字规范。

分类 `semantic.category.id0..id7` 保留用户数据中 `colorId` 的顺序：蓝、金、玫瑰、绿、紫、陶橙、青、灰。每组有 `accent`（原色点）、`foreground/background`（浅色配对）、`border`、`solid/onSolid`（实心配对）。类别不等于 task/event/status/reminder 类型；选择、当前、完成和错误分别用状态语义。

## 字体、尺寸与单位

业务字体采用系统无衬线和中文回退；时间可用等宽数字，等宽字体限于代码/ID。Comfortaa Bold 只属于 Hop 小时数字。正文不使用 Oswald 或 IBM Plex Mono 作为跨端强制字体。

| 平台 | heading / body / taskTitle / meta | 控件最小高 | 触摸目标 | 任务行最小高 |
| --- | --- | --- | --- | --- |
| Web | 24 / 15 / 15 / 12 | 40 | 44 | 44 |
| Desktop | 24 / 15 / 15 / 12 | 36 | 44 | 44 |
| 手机 | 22 / 16 / 15 / 13 | 48 | 48 | 52 |
| Wear 应用 | 20 / 15 / 17 / 12 | 48 | 48 | 58 |

这些是最小尺寸或基准，允许内容撑高；不能固定高度后裁掉放大的文字。Web 触屏使用触摸目标值，桌面鼠标可用紧凑控件值。手机 Web 输入框应至少 16 CSS px，不能直接沿用桌面的 15px 输入样式。

源中的 dimension 用逻辑 `px` 表示设计尺度。CSS 字体尺寸和字距导出为以 16px 为基准的 `rem`，其余为 CSS px；Kotlin 保留数值，Android 字号映射 sp、几何映射 dp，再由平台转换到像素。不要把导出的数值直接传给 Canvas 当设备 px。Android 使用平台 sans-serif 字体族，不把 CSS fallback 列表原样传给 Typeface。

通用间距使用 2 / 4 / 8 / 12 / 16 / 24 / 32 / 48；常用圆角为小标记 4、控件 8、卡片 12、对话框 16、胶囊 9999（各端按半高裁切）。状态使用显式颜色，已完成任务保持整行 opacity=1，并用划线、图标和前景层级表达。

动效使用 120 / 200 / 240ms，减少动态效果时取 `instant=0`。这些值由组件选择使用；仅加载变量不会自动关闭已有动画。网页须接 `prefers-reduced-motion`，Android 遵循平台动画设置。

## 白边与纵深

业务卡片使用 1 单位浅亮沿和低强度阴影；焦点、输入边界仍使用明确的深色语义。`component.panel` 定义普通层级，`component.dialog` 定义浮层；不要每个任务行都加阴影。

Hop 的固定白沿、偏向顶部/右侧的内凹渐变独立保存在 `component.hop`，比例和 alpha 对齐当前 painter。白沿宽度是 `max(rimMin, D × rimDiameterRatio)`，渐变按对应 stop/alpha 组合，不能用普通 CSS 外阴影替换后声称原生表盘等价。

## 接入示例

Web / Desktop 的每一个文档都显式选择浅色：

```html
<html data-tp-theme="light">
  <!-- 将文件纳入各端本地构建产物，不在运行时引用其他 worktree -->
  <link rel="stylesheet" href="./tplanner-light.css">
</html>
```

```css
.task-title {
  color: var(--tp-semantic-color-text-primary);
  font-size: var(--tp-platform-desktop-typography-task-title-font-size);
}
```

```ts
import { createTheme } from '@mui/material/styles';
import { createLightThemeOptions, categoryForId } from './adapters/mui-light';
const theme = createTheme(createLightThemeOptions('desktop'));
const category = categoryForId(item.colorId);
// 浅色卡片使用 category.background + category.foreground。
```

原有 MUI `palette.mode: dark`、静态主题覆盖和 Electron 独立文档必须一起适配；仅安装 CSS 变量不够。此 adapter 覆盖基础按钮、链接、输入、checkbox 和对话框，其余业务组件按迁移地图接入。

```js
import { lightTokens } from './tplanner-light.mjs';
const colors = lightTokens.semantic.color;
```

Android 正式接入时，将生成的对象并入现有唯一 ARGB 源 `TPlannerDesignTokens.kt`，或让生成器管理该源中的明确区块；本次不建立第二个生产颜色源。现有 `TPlannerColors` 仍服务 legacy，不能直接全部替换为浅色而遗漏前景、混色和状态。迁移批次再运行品牌检查与相应客户端构建。

当前 Web 时间轴的 27px 摘要高度参与叠放算法；新字号不能直接替换其中的 10px 时间标签而保留原几何。颜色接入与布局迁移应分批验证。
