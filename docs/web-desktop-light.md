# Web / Desktop 浅色换肤验收

2026-09-08。本批在 `C:/Users/hamhuo/tplanner/worktrees/master`（起点 `4996f55`）完成，交付分支为 `master`。覆盖 Web、Electron 主窗口及 Today / Notes 独立小窗。Android / Wear 由并行任务负责；共同源为 `111b90d` 的浅色令牌包。

## 统一输入与复用

先检查现有页面、时间轴算法及独立窗口入口，再沿已有组件接入统一主题。没有修改事件结构、同步协议或持久化的分类 ID。

| 界面 / 层级 | 复用与迁移结果 |
| --- | --- |
| 主题与通用控件 | 本地消费原样生成的 CSS / JS 和 MUI adapter；tokens.js 提供兼容映射及平台 profile；primitives.css 统一面板、浮层、输入、菜单、焦点和遮罩 |
| 登录、主窗口、提示与工具 | 登录表单、画布、顶栏、提醒 / 逾期 / 冲突提示、菜单、同步浮层、调试与缩放入口使用统一语义；Electron 主窗口启动背景使用 canvas |
| 时间轴与详情 | 继续复用 TaskUnit / TaskCheckbox / TaskProgress；分类背景与前景成对使用；详情子项复用勾选和进度；保留时间轴几何和子项阻塞行为 |
| 创建表单 | 分类改为有选中语义的按钮；日期、时间和重复控件按窄屏换行；普通输入与日期选择器焦点统一为深橙色 |
| 便签与日记 | 抽取共享 MarkdownPreview，覆盖内联、只读、全屏和日记；正文、清单、代码、表格与图片统一样式 |
| Today / Notes 小窗 | 抽取 shared-widget.css 与 widget-shared.mjs，共享窗口表面、字体、标题控件和分类读取；资源完整复制进 dist-electron |

令牌副本与生产代码位于同一工作树，运行和打包不依赖另一工作树。来源及每个文件的 SHA-256 见 [provenance.json](../design-assets/tokens/provenance.json)，同步方法见 [消费包说明](../design-assets/tokens/README.md)。源 JSON SHA-256 为 `3c3f45d1a715d2b8ff146bcaad37d5cf6a7b35787d5b12e67d1d3f86bbd231fb`，与 Mobile / Wear 负责人确认一致。

## 验收结果

| 检查 | 结果及范围 |
| --- | --- |
| npm run build | TypeScript、Web 和 Electron 主进程 / preload 构建通过；保留既有 chunk 体积、动态导入和 Vite 配置兼容性提示 |
| 令牌一致性 | sync-light-tokens.mjs --check 通过，7 个消费文件与固定提交一致 |
| 源包检查 | 根仓 generate-design-tokens.py --check 通过：393 个令牌、原 5 份导出、54 组必需对比检查；保留源包 2 项已知 Hop 诊断 |
| 补充状态对比 | 浏览器实际分类标题前景 / 背景最低约 5.05:1；完成态约 5.43:1。完成 / 已过整行 opacity=1；这不是整个应用的 WCAG 合规声明 |
| 响应式 | 实际检查 1280px 主界面、390px 登录 / 创建、320px 创建及 360px / 240px 小窗，无横向溢出；输入窄屏 16px，Web 控件 40px、Desktop 控件 36px |
| 表单与详情 | 分类选择、创建后的时间轴条目、子项勾选到 3/3 与父任务完成通过；普通字段及 MUI picker 的聚焦标签 / 边界实际为 focus 深橙色 |
| Markdown | 内联 / 全屏 / 只读共享渲染、Escape 关闭全屏通过；Notes 小窗编辑、失焦保存及预览通过 |
| Today | 八类、现在 / 即将 / 已过 / 完成、未完成子项阻塞及解除阻塞后完成通过；完成优先于时间状态 |
| 打包资源 | 真实 Electron 隐藏窗口以 file:// 加载小窗产物通过；11 个复制资产一致，12 条本地引用完整；4 个 CJS 运行入口齐全；node 语法检查通过 |
| 生产隔离 | dist / dist-electron 无离线预览、fixture 或截图引用；Windows / Linux 打包配置不纳入验收脚本与文档 |
| 覆盖复查 | 52 个源文件未发现遗漏的可达暗色页面或未定义颜色变量；剩余黑白字面值为打印样式，拖动隐藏与入场动画保留各自透明度 |
| 差异检查 | git diff --check 通过；最终离线预览无控制台 error，热更新后仍可渲染 |

交互检查使用真实组件的内存 fixture，不读取生产账号、数据库或同步链路；真实生产登录页另有响应式检查。减少动态效果已接入 CSS，但未模拟系统设置。可见 Electron 窗口的原生拖动、置顶、透明边角、关闭后重开和真实 IPC 尚需实际桌面验收。

## 截图

截图均来自真实组件或实际小窗产物，示例数据仅用于验收。

| 页面 | 截图 |
| --- | --- |
| Web 主界面 | [timeline-web.jpg](screenshots/light/timeline-web.jpg) |
| Desktop 主界面 | [timeline-desktop.jpg](screenshots/light/timeline-desktop.jpg) |
| 登录 / 窄屏登录 | [login-web.jpg](screenshots/light/login-web.jpg) / [login-mobile.jpg](screenshots/light/login-mobile.jpg) |
| 窄屏创建表单 | [add-mobile.jpg](screenshots/light/add-mobile.jpg) |
| 子任务详情 | [task-details.jpg](screenshots/light/task-details.jpg) |
| Markdown 编辑 | [notes-editor.jpg](screenshots/light/notes-editor.jpg) |
| Today / Notes 小窗 | [today-widget.jpg](screenshots/light/today-widget.jpg) / [notes-widget.jpg](screenshots/light/notes-widget.jpg) |

## 冲突和未定义项

跨任务公共记录位于根仓 [web-desktop-skin-coordination.md](../../../docs/web-desktop-skin-coordination.md)。下列决定已经应用，后续修改应先协调，再更新所有导出和消费者。

| 编号 | 当前决定 / 后续需要 |
| --- | --- |
| WD-01 | 时间轴仍使用 27px 摘要、34px 最小事件和 16px 状态行；10px 紧凑时间文字属于已记录例外。迁移到 meta 12px / taskRow 44px 必须同步修改排布算法及重叠规则 |
| WD-02 | 本批固定统一浅色；旧 .tptheme 接口未形成 CSS、MUI、独立窗口一致的运行时主题系统 |
| WD-03 / WD-04 | 旧装饰色归并至现有语义；colorId 0..7 顺序不变，分类与类型 / 状态保持独立；分类命名如需跨端对齐另行协调 |
| WD-05 | 源包缺少专门 scrim 令牌；共用遮罩暂以 dialog.shadowColor 的 32% 合成表达，后续应由源包给出明确角色 |
| WD-06 | textMuted 放在 disabledBackground 上仅约 4.405:1，故完成 / 已过正文使用普通 surface；disabledBackground 不用作完成条目正文底色 |
| WD-D01 | 小窗最窄 240px，与 Desktop pageInset 24px 及三个标题控件冲突；外壳使用 spacing.block 12px，正文 spacing.inline 8px |
| WD-D02 | 原生 Electron 阴影保留 hasShadow；API 不能直接表达 CSS shadow blur / opacity，不声明两者数值完全相同 |
| WD-T01 | MUI X focus specificity 和未覆盖的 picker / Switch / menu 样式在应用层补全；共享适配器是否吸收这些补全由令牌任务协调 |

详细实现说明：[主题适配](web-desktop-theme-notes.md)、[业务组件](web-component-light-notes.md)、[Desktop 小窗](desktop-light-notes.md)。这些分工文档有各自局部编号，以上表格与根仓协调表为跨任务索引。

## 复现预览

在本工作树运行两个独立终端：

```powershell
node scripts/ui-review-server.mjs
```

```powershell
npm run build
node scripts/widget-review-server.mjs
```

主界面：<http://127.0.0.1:4174/scripts/ui-review.html>，加 `?profile=desktop` 切换桌面 profile。Today：<http://127.0.0.1:4175/widget.html>；Notes：<http://127.0.0.1:4175/notes-widget.html>。表单、详情、便签和登录的直接入口见 [离线 UI 说明](../scripts/ui-review.md)。两服务只监听本机，刷新恢复示例；不加入生产入口。服务日志应放临时目录，不放入被桌面打包的 dist-electron。

令牌复核命令：

```powershell
node scripts/sync-light-tokens.mjs --check --source-root C:/Users/hamhuo/tplanner
```
