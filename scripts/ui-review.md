# 离线 UI 验证入口

在 Web/Desktop 工作树运行：

```powershell
node scripts/ui-review-server.mjs
```

服务仅监听 `127.0.0.1:4174`，端口占用时直接失败，不自动换端口。按 Ctrl+C 停止。

- Web：<http://127.0.0.1:4174/scripts/ui-review.html>
- Desktop 主窗口：<http://127.0.0.1:4174/scripts/ui-review.html?profile=desktop>
- 创建表单：<http://127.0.0.1:4174/scripts/ui-review.html?view=add>
- 子任务详情：<http://127.0.0.1:4174/scripts/ui-review.html?view=details>
- 便签：<http://127.0.0.1:4174/scripts/ui-review.html?view=notes>
- 登录：<http://127.0.0.1:4174/scripts/ui-review.html?view=login>

参数可组合：`profile=desktop` 切换真实 Desktop profile 与真实 TitleBar；`lang=en` 使用英文；`clean=1` 隐藏恢复/状态栏及登录页返回按钮，便于截图。顶栏按钮可直接打开其余界面。登录仅在内存接受示例 `demo` / `demo`，其他输入返回真实登录组件错误样式。

入口使用真实的 Timeline、EventRow/EventBlock、三种提示 banner、AddEventModal、EventDetailsModal、NoteEditor、LoginScreen、TitleBar、MUI theme 与令牌安装器。复用 App 的 header、banner、main 和 timeline 层级及样式，不导入 App、数据库、认证或同步模块。Vite 使用 `configFile: false`，不载生产 Vite 的 Electron plugin 或 sync proxy。页面 CSP 只允许本地资源和同源开发服务，服务主动拒绝 `/api`、`/tplanner` 请求。

浏览器此独立文档固定时钟为 **2026-09-08 10:30 Asia/Shanghai**，方便重复检查当前/即将/逾期状态。Date shim 只存在于此入口，不修改生产时间模块。Desktop stub 只有标题栏所需五个方法；最大化切换仅更新图标，最小化/关闭不操作真实窗口。

示例覆盖：

- 八个稳定 `colorId`，其中 category-7 初始为选中状态。
- 当前提醒、逾期任务、三行重叠 status 条、两个 event 冲突、带未完成子项的阻塞任务、已完成 shadow。
- 5 分钟短事件、23:40–00:20 跨日事件、长中文标题与子任务文案。
- Markdown 标题、强调、引用、清单、链接、代码、空白/编辑/只读/全屏状态。

点击事件查看详情，完成全部子项后主任务随之完成；创建、编辑、删除、拖动、框选、便签保存仅修改 React 内存。刷新页面恢复 fixture，底栏“恢复示例”恢复事件和便签。日期列可打开真实日记浮层；时间轴空白处可打开创建表单。可将浏览器改为 390px、768px、1440px 宽度检查页面滚动和浮层。

该入口用于可复现的 UI 验证，不证明 Electron 原生 IPC、窗口外形或生产网络链路通过；这些由各自真实运行入口验收。
