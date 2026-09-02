# tPlanner Sync V3 运维与灾备 Runbook

> 面向树莓派单机部署。架构与不变量见 `docs/sync-v3.md`,本文只写"出事了怎么处理"。

## 1. 组件与端口

| 组件 | systemd 单元 | 端口 | 用户 |
|---|---|---|---|
| NATS JetStream | `nats-server.service` | 127.0.0.1:4222(监控 8222) | tplanner |
| State Builder | `tplanner-state-builder.service` | 无(出站连 NATS) | tplanner |
| V3 API | `tplanner-sync-api.service` | 127.0.0.1:37401 → Caddy 443 | tplanner |

目录:`/opt/tplanner-sync`(代码)、`/var/lib/tplanner-sync/{state,jetstream,backups}`(数据,**必须放在 SSD 上**)。

> **认证边界(硬前提)**:API 进程没有应用层认证 —— `/command-batches` 只校验 schema 与 Idempotency-Key,`/receipts` 的 deviceId 是自报。127.0.0.1 绑定 + **外层 Cloudflare Access / OIDC / mTLS 之类的 authenticated perimeter 是上线硬前提;浏览器 CORS 不是认证**。没有外层鉴权就只允许局域网访问,否则公网用户可用 curl 直接调 API。

## 2. 日常检查

```bash
systemctl status nats-server tplanner-state-builder tplanner-sync-api
journalctl -u tplanner-state-builder -n 50          # 关键日志:integration batch committed / lease
curl -s http://127.0.0.1:37401/health/live          # 进程存活
curl -s http://127.0.0.1:37401/health/ready         # 全链路就绪(含 materializer lag)
curl -s http://127.0.0.1:37401/tplanner/v3/status   # latestVersion / queueLag / storage.* / metrics.*
sudo -u tplanner npm --prefix /opt/tplanner-sync/sync-server run validate:journal
```

健康判读:

- `/health/ready` 非 200 或 `queueOldestAgeMs > 10s` → 看 State Builder 日志,常见原因是 reducer 抛错进入重投循环(§5:绝不跳过,所以表现为积压)。
- `materializedThroughSequence` 长时间不变 → State Builder 卡死或 lease 被抢,`journalctl` 查 `lease renewal failed`。
- 两个 builder 同时存在 → 后启动者必然因 lease 失败退出;若两个都活着,检查是不是两个数据库文件路径(环境变量不一致)。
- **journal shadow validation FAILED(builder 日志)= P0 correctness 告警**:立即按 §5 关闭 delta(`TPLANNER_ENABLE_DELTA` 摘除)再排查,不得当普通 warning 处理。shadow validator 在启动时全链验证一次,之后在主循环迭代边界约每 60s 尾部验证一次(与流量无关,不是挂在空闲分支上)。
- `/tplanner/v3/status` 的 `storage.journalMinVersion` 是 retention 推进后的 journal 起点;`metrics.counters.snapshot_fallback_total` 按 reason 分解,`CURSOR_EXPIRED` 属设计内,异常升高要查。

## 3. 备份与恢复

备份(见 `sync-server/deploy/backup.sh` 与 `src/state/backup.js`):

- **SQLite 在线备份**:`sudo -u tplanner /opt/tplanner-sync/sync-server/deploy/backup.sh`(底层是 `scripts/backup.mjs`,内部走 SQLite Online Backup,写入期间服务不中断);`tplanner-sync-backup.timer` 每小时自动跑一次。
- **JetStream**:单节点 file store,无写入窗口时整目录 `tar`;有写入时以 SQLite 备份 + JetStream 重放为准。
- **异机**:`backups/` 用 restic/rclone 每日推离树莓派。**MQ 和 SQLite 放在同一张坏掉的 SD 卡上不构成冗余。**
- **V4 cursor 密钥**:`/var/lib/tplanner-sync/state/tplanner.db.cursor-secret` 随 `config-latest.tar.gz` 一起备份;恢复时放回同路径。密钥丢失不会丢数据 —— 旧 cursor 全部 410,客户端按设计走 full snapshot 并重建 delta 起点。

恢复流程(DB 损坏):

```text
1. systemctl stop tplanner-sync-api tplanner-state-builder
2. 恢复最近 SQLite backup 到 /var/lib/tplanner-sync/state/tplanner.db
3. 从备份记录读取 materializedThroughSequence(= 该库已消费到的 broker 序列)
4. 启动 State Builder:durable consumer 自动从下一序列重放(命令幂等,重放安全)
5. 生成一个更高版本的快照(版本号只前进,不倒退)
6. 校验 /health/ready 与 /tplanner/v3/status
7. 启动 API;客户端收到新 version 后自动拉取安装
```

恢复流程(JetStream 目录损坏,SQLite 完好):

```text
1. 停 API 与 builder
2. 重建 JetStream(install.sh 的 stream 创建是幂等的)
3. 命令流丢失 = 上游命令丢失:以 SQLite 状态为准,继续服务
4. 客户端 outbox 中未被回执确认的命令会自动重新上传(客户端拿到 BROKER_PERSISTED 前不删 outbox)
```

## 4. 单写者租约

- 租约 30s TTL,每 10s 续期;续期失败 → builder 主动退出,systemd 重启后重新抢租约。
- 手工接管:`systemctl stop tplanner-state-builder` 后等待 TTL 过期,再启动新实例。
- **禁止**手动改 `state_builder_lease` 表;需要强制接管时直接清空该表后立即启动 builder(瞬间完成,风险窗口为 TTL 一次)。

## 5. 升级与回滚

- DB migration 是 expand-only(`user_version` 只增);升级前先做一次在线备份。
- **V4(change journal)**:migration 004 自动套用,不新增手工步骤;`sync_journal_meta.min_snapshot_version` 落在升级当刻的 snapshot head,**历史不回填** —— 升级后新产生的 snapshot 才开始双写 journal。首次 `/changes` 请求前 retention 只按配置推进。
- delta 下行默认关闭:`TPLANNER_ENABLE_DELTA=1` 才在 capabilities 暴露 `delta-v1`(写入 API unit 的 Environment)。回滚/刹车 = 摘掉该变量并 `systemctl restart tplanner-sync-api`,客户端立即退回 snapshot;journal 数据保留,无需 down migration。
- retention 可调:`TPLANNER_JOURNAL_MAX_COMMITS`(默认 100000)、`TPLANNER_JOURNAL_MAX_AGE_DAYS`(默认 30)。
- **降级警告**:把服务器代码回滚到不会 dual-write journal 的旧版本后,若再次升级并重新打开 delta,必须先 bump `sync_journal_meta.journal_epoch`(或保持 delta 关闭),否则 journal 中间有 gap。
- 回滚只能回到兼容同一协议版本的代码;不能倒 user_version。
- 客户端拿到 `BROKER_PERSISTED` 前不删本地 outbox —— 服务器在整合前故障时,客户端仍保有原始命令,这是回滚的最后防线。
- `serverInstanceId` 变化(数据宇宙重建)后:客户端必须重新 bootstrap,不要沿用旧版本号。

## 6. 日志

- 全部结构化 JSON(pino → journald),共享字段:`traceId/deviceId/batchId/commandId/clientSequence/brokerSequence/snapshotVersion/projectionVersion`。
- 严禁记录:日记正文、note 全文、AI 密钥、Authorization、完整快照 payload。
- 客户端报错 `ERROR006`(hash 校验失败)时,用 `traceId` 反查服务器日志。

## 7. 常见故障速查

| 症状 | 第一动作 |
|---|---|
| 客户端"已上传,正在整合"很久 | `/tplanner/v3/status` 看 queueLag;查 builder 日志是否在重投循环 |
| 客户端永远"正在更新" | `GET /tplanner/v3/snapshots/latest` 手工比对 hash;查 API 日志 |
| 快照 hash 校验失败(ERROR006) | 客户端本地数据可能被改坏;重新 bootstrap 并查服务器 snapshot 表对应版本 |
| 某设备长期 SEQUENCE_GAP | 该设备重装/恢复过备份:调用 `device.reset` 或让其重新生成 deviceId |
| 所有端都收不到更新 | 查 `publication_outbox` 是否积压 pending;重启 builder 会先补发 |
| SD 卡告警 | 立即执行 §3 备份 + 异机推送;安排迁移到 SSD |
