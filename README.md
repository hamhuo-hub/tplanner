# tPlanner Desktop + Web

`master` 只承载桌面端、Web 前端与共享 Sync V3 协议。Android/Wear 在
`mobile_andorid`，中央服务在 `sync_server`；三个分支共用协议目录
`sync-v3/protocol/v3`，CI 要求其内容逐字节一致。

当前统一发布号：**8.0.0**（Sync V3 全量切换）。

## 同步架构

客户端只执行单向 V3 流程：本地操作写入 semantic-command outbox，中央单写者排序并生成不可变快照，客户端原子安装 Server Mirror 后重放仍未确认的 pending 命令。客户端不再进行三方合并，也不再读写 V1 dataset 路由。

生产地址：

- Web：`https://plan.hamhuo.top`
- Sync V3：`https://sync.hamhuo.top/tplanner/v3`

Web 生产站由 Caddy 独立提供 `/srv/tplanner-web/current` 静态文件，并只把
`/tplanner/*` 反代到本机 V3 API。部署 Web 不复制服务器代码，也不重启
Sync API、NATS 或 State Builder。

## 本地开发

```bash
npm ci
npm run dev
npm test
npm run build
```

Vite 开发服务器把 `/tplanner` 代理到 `TPLANNER_SYNC_PROXY_TARGET`（默认
`https://sync.hamhuo.top`）。若要让浏览器直接跨源访问另一套 V3 服务，构建时设置 `VITE_SYNC_SERVER_URL`。

## Web 部署

具备 `hamhuo@192.168.1.9` SSH 权限的局域网主机执行：

```bash
npm run deploy:web
```

脚本会构建 `dist/`，上传为 `/srv/tplanner-web/releases/<release>`，校验并热重载 Caddy，再原子切换 `current` 链接；健康检查失败会恢复旧 Caddy 配置与旧链接。可用 `PI_HOST`、`PI_USER`、`PI_WEB_ROOT` 覆盖部署目标。

## 分支边界

- 本分支不包含或部署 `sync-server/`。
- 禁止重新引入 `/tplanner/events`、`/journals`、`/changes` 等 V1 路由。
- 新业务字段必须同时进入 command、中央 reducer、snapshot、三端 mapper 与 round-trip 测试；不支持的字段必须保留，不能由同步清空。
