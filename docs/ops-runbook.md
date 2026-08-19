# tPlanner Sync V3 运维与灾备 Runbook

> 面向树莓派单机部署。架构与不变量见 `docs/sync-v3.md`,本文只写"出事了怎么处理"。

## 1. 组件与端口

| 组件 | systemd 单元 | 端口 | 用户 |
|---|---|---|---|
| NATS JetStream | `nats-server.service` | 127.0.0.1:4222(监控 8222) | tplanner |
| State Builder | `tplanner-state-builder.service` | 无(出站连 NATS) | tplanner |
| V3 API | `tplanner-sync-api.service` | 127.0.0.1:37401 → Caddy 443 | tplanner |

目录:`/opt/tplanner-sync`(代码)、`/var/lib/tplanner-sync/{state,jetstream,backups}`(数据,**必须放在 SSD 上**)。

## 2. 日常检查

```bash
systemctl status nats-server tplanner-state-builder tplanner-sync-api
journalctl -u tplanner-state-builder -n 50          # 关键日志:integration batch committed / lease
curl -s http://127.0.0.1:37401/health/live          # 进程存活
curl -s http://127.0.0.1:37401/health/ready         # 全链路就绪(含 materializer lag)
curl -s http://127.0.0.1:37401/tplanner/v3/status   # latestVersion / brokerLastSequence / materializedThroughSequence / queueLag
```

健康判读:

- `/health/ready` 非 200 或 `queueOldestAgeMs > 10s` → 看 State Builder 日志,常见原因是 reducer 抛错进入重投循环(§5:绝不跳过,所以表现为积压)。
- `materializedThroughSequence` 长时间不变 → State Builder 卡死或 lease 被抢,`journalctl` 查 `lease renewal failed`。
- 两个 builder 同时存在 → 后启动者必然因 lease 失败退出;若两个都活着,检查是不是两个数据库文件路径(环境变量不一致)。

## 3. 备份与恢复

备份(见 `sync-server/deploy/backup.sh` 与 `src/state/backup.js`):

- **SQLite 在线备份**:`node src/state/backup.js` 内部走 SQLite Online Backup,写入期间服务不中断;建议 cron 每小时一次。
- **JetStream**:单节点 file store,无写入窗口时整目录 `tar`;有写入时以 SQLite 备份 + JetStream 重放为准。
- **异机**:`backups/` 用 restic/rclone 每日推离树莓派。**MQ 和 SQLite 放在同一张坏掉的 SD 卡上不构成冗余。**

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
