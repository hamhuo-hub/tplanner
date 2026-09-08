# Web / Desktop 浅色令牌消费包

本目录是统一令牌提交 `111b90d` 的只读副本。人工维护源位于 canonical 仓库的 `design-assets/tokens/tplanner-light.tokens.json`，本工作树不另建色板。`provenance.json` 记录完整来源提交和每个文件的 SHA-256（UTF-8 / LF 规范化，允许 Git 在 Windows 签出 CRLF），CSS / TS / MJS 与 MUI adapter 原样同步，不直接修改。

从 Web / Desktop 工作树执行：

```powershell
node scripts/sync-light-tokens.mjs --check
node scripts/sync-light-tokens.mjs --check --source-root C:/Users/hamhuo/tplanner
# 确认跨端协调后的新版本才显式同步；来源路径仅供开发使用。
node scripts/sync-light-tokens.mjs --source-root C:/Users/hamhuo/tplanner --source-ref 111b90d
```

主窗口由 `src/main.tsx` 加载生成 CSS，`src/design-system/tokens.js` 提供语义及兼容接口，`src/theme.js` 消费原始 MUI factory 并补全实际业务使用的组件。Electron 独立窗口由本地打包步骤复制 CSS / MJS，不运行时访问其他工作树。

`accentText` 用于浅底文字，`accent` 用于强调填充；分类 `id0..id7` 及其 `foreground/background` 配对保持原顺序。完成与 shadow 状态整行 opacity 为 1。主窗口以 16px 根字号解释令牌 rem；平台控件尺寸通过 `data-tp-platform` 和 `--tp-profile-*` 别名接入。

时间轴的 27px 摘要、34px 事件最小高、16px 状态行及紧凑时间标签保留，详见 [协调记录](../../docs/web-desktop-theme-notes.md)。
