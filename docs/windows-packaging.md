# Windows 打包

在 Web / Desktop 工作树运行 `npm run package`，输出位于 `release/`：NSIS 安装程序和 portable 可执行文件。版本仍从 Git 标签计算；HEAD 没有精确标签时使用最近版本的 `-dev`，不需要为了打包创建新标签。

2026-09-08，在 Windows 10.0.26200、Node 26.4.0、electron-builder 26.15.3、Electron 41.10.6 上复现了 `win-unpacked.tmp` 重命名到 `win-unpacked` 的 EPERM。失败发生在依赖的归档解压收尾，该位置只有一次 rename；没有发现运行中的 release 程序，空目录重命名也通过。现有证据不足以断定是杀毒软件或某个进程的文件占用。

`scripts/package.mjs` 现通过官方支持的 [electronDist](https://www.electron.build/docs/api/app-builder-lib.interface.configuration/#electrondist) 复用 `node_modules/electron/dist` 的已解压运行时，避开重复下载 / 解压后的目录重命名。Windows 下先检查运行时版本与已安装包一致，并读取 PE header 确认 x64，与两个 Windows 打包目标一致；检查不通过会停止并提示重新安装依赖。其他主机保留 electron-builder 的常规运行时获取方式。

不需要关闭用户正在使用的应用、修改 node_modules 或删除旧安装包。若之后报错指向正在运行的 `release/win-unpacked/tPlanner.exe`，需要正常退出该开发产物后再打包；脚本不会自动结束用户进程。失败遗留的 `win-unpacked.tmp` 不是安装程序，也不影响新的复制路径。

Git 的版本探测现在捕获缺少精确标签的预期诊断，避免在正常开发版本构建前打印误导性的 `fatal: no tag exactly matches`。真实 electron-builder 错误仍保留完整输出。

相同主机已通过完整 NSIS / portable 打包，日志确认使用 custom unpacked Electron distribution。前端仍为已验收的 `1c7c544` 浅色版本；运行时保持 Electron 41.10.6。
