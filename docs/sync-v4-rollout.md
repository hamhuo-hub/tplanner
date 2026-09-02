# TPlanner Sync V4 — Canary / Chaos / Production Rollout Gate

> V4 定义:**不重做 V3,不换 command model,不引入 CRDT,不让 client merge remote state。**
> 只把 snapshot-only 下行升级成 delta-first + snapshot fallback。
> wire 上 `protocolVersion` 仍是 3,新增 `deltaVersion = 1`;snapshot schema 仍是 3。

本文件是 Phase 8 的上线门槛:任何一档 canary 触犯 correctness gate 或 metrics gate,
立即把 `capabilities.downlinkModes` 改回 `["snapshot"]`(去掉 `TPLANNER_ENABLE_DELTA=1`),
新版客户端马上退回 snapshot;数据库 journal 保留,无需 down migration。

## 0. 已完成的地基(每项都有自动化测试)

| 不变量 | 证明位置 |
|---|---|
| Snapshot(N) + Commit(N+1) == Snapshot(N+1) | sync-server `test/journalValidator.test.js`(shadow validator)+ `journal.test.js` reconstruction property;桌面 `deltaInstaller.test.js`;Android `SyncV4DeltaInstallerTest.kt` |
| 每个 snapshotVersion 恰一个 journal commit(含 empty NOOP/REJECTED coverage) | `journal.test.js` / `journalValidator.test.js` |
| journal 与 snapshot 同一 SQLite 事务 dual-write(所有 5 个 snapshot producer) | `journal.test.js` 原子性用例 |
| 重复 commandId / (deviceId, clientSequence) 不重复执行、不重复 journal | `materializer.test.js` / `crashRecovery.test.js` / `journal.test.js` |
| cursor 只在本地事务成功后推进 | 桌面 `deltaInstaller.test.js`(IndexedDB 失败 cursor 不动);Android `RoomSyncV3ProjectionInstallerTest`(进程死亡整体回滚) |
| pending command 在 delta 后仍正确 overlay | 桌面 `deltaInstaller.test.js`;Android `RoomSyncV3ProjectionInstallerTest` |
| 断链/未知 type/410 → full snapshot,绝不猜测修补 | 桌面/Android delta installer 测试 + `changes.test.js` |
| retention 单调推进 min_snapshot_version,旧设备 410 | `changes.test.js` |

## 1. Correctness gate(canary 扩大前必须全绿)

1. `Snapshot(N) + Delta(N+1) == Snapshot(N+1)`(JCS stateHash,逐 checkpoint)
2. 每个 snapshotVersion 恰一个 journal commit;parentVersion 连续;empty commit 不丢
3. 重复 commandId 不产生重复 journal;重复 (deviceId, clientSequence) 不重复执行 state
4. cursor 只在本地事务成功后推进;进程死亡 = 整体回滚
5. pending command 在 delta 后仍能正确 overlay;terminal receipt + 证明覆盖才清 outbox
6. 任何 410 / parent gap / unknown type / hash 失配 / schema 不符 → full snapshot 逃生舱
7. 旧 snapshot-only 客户端与新 delta 客户端并行工作(snapshot 端点永不删除)

## 2. Chaos test 清单(必须主动制造,不是等它自然发生)

| 故障注入 | 期望结果 |
|---|---|
| State Builder 在 reducer 后、commit 前崩溃 | 事务回滚,内存/DB 均停在旧状态,重投重放 |
| State Builder 在 snapshot/journal transaction 中崩溃 | 同事务整体回滚,无半 commit |
| JetStream 重投(ack 丢失/延迟) | command_id + (device_id, client_sequence) 幂等,无重复 journal |
| SQLite BUSY(写锁竞争) | busy_timeout 5s 内等待或明确失败重试;无脏写 |
| 主机断电后重启 | WAL 恢复;ensureReceiptCoverageSnapshot / bootstrap 幂等;shadow validator 全链验证通过 |
| Cloudflare tunnel 暂断 | 客户端 outbox 不丢;重连后 broker 去重;无重复实体 |
| 客户端收到 delta 后、本地事务 commit 前进程死亡 | 整体回滚;重启用旧 cursor 重放同一页,幂等跳过 |
| 客户端 cursor 已旧到被 retention GC | 410 → full snapshot → 安装 manifest.cursor 重建起点 |
| 客户端拿到未知 delta type | fail closed → full snapshot |
| 客户端拿到 parentVersion mismatch | fail closed → full snapshot |
| snapshot 表/行被篡改(state_hash 列或载荷) | shadow validator 报 P0(§9.2),delta 不下发 |

验证的不是"程序最终没挂",而是:**没有半提交、没有跳 cursor、没有重复 state mutation、
没有 journal 断链,任何异常最终都能 snapshot recovery。**

## 3. Production metrics gate

`GET /tplanner/v3/status` 已提供 `storage.*` 与进程内 `metrics.*`;上线后必须补齐长时序(日志聚合/时序库):

```text
state_builder_commit_duration_ms     journal_head_version
materializer_broker_lag              journal_min_version
journal_bytes                        snapshot_bytes
sqlite_wal_bytes                     sqlite_busy_total
delta_request_duration_ms            delta_response_bytes
snapshot_response_bytes              cursor_lag_versions
cursor_age_seconds                   snapshot_fallback_total{reason}
delta_apply_failure_total            state_hash_mismatch_total
unknown_delta_type_total             nats_redelivery_total
tunnel_health
```

**P0 规则:`state_hash_mismatch_total > 0`(或 builder 日志出现 journal shadow validation
FAILED)直接按 correctness incident 处理 —— 不是 warning。** 触发即:关闭 delta capability →
修复/回滚 → shadow validator 全链重验通过 → 重新开放。

retention 起始值(工程起始,非协议不变量):`TPLANNER_JOURNAL_MAX_COMMITS=100000`、
`TPLANNER_JOURNAL_MAX_AGE_DAYS=30`,按 `cursor_age_seconds` / journal bytes 实测量调优。

## 4. Rollout 顺序(不允许跳档)

```text
0%   只 shadow validate(TPLANNER_ENABLE_DELTA 不设置)
  ↓
1%   内部 Desktop/Web canary(设置 TPLANNER_ENABLE_DELTA=1)
  ↓
5%   Desktop/Web canary(观察 snapshot_fallback_total 按 reason 分布)
  ↓
10%  Android canary(SyncV3Engine.deltaEnabled = true 的新构建)
  ↓
25%  混合平台
  ↓
50%
  ↓
100% 新版本默认 delta
```

每一档的自动刹车条件(任一命中即 `downlinkModes=["snapshot"]`):

- state hash mismatch > 0 / shadow validator 失配
- delta apply failure 明显上升(客户端 telemetry)
- snapshot fallback 异常升高(按 reason 分解;DELTA_DISABLED 与 CURSOR_EXPIRED 是设计内)
- cursor corruption / 跨设备 convergence regression

## 5. V4 完成定义(design freeze)

```text
上行:      semantic commands → JetStream → State Builder → idempotent terminal receipts
下行正常:  opaque cursor → /changes → typed authoritative entity replacement delta
恢复路径:  full immutable snapshot(永远保留 bootstrap/recovery 能力)
客户端:    server mirror + pending commands overlay
正确性:    server authority + atomic commit + idempotence + snapshot fallback
```

不在 V4 范围(明确排除):CRDT、WebSocket、multi-region State Builder、PostgreSQL 迁移、
per-field delta patching、physical tombstone deletion、snapshot schema 4。

## 6. 发布检查单(v4.0.0 release criteria)

- [ ] sync_server PR1–PR5 全绿(含 reconstruction property 与 410/分页矩阵)
- [ ] shadow validator 连续运行 N 天零失配(建议 ≥ 7 天)
- [ ] Desktop/Web delta installer 测试全绿,canary 阶段 fallback 按 reason 分布正常
- [ ] Android Room delta installer 测试全绿(进程死亡/离线积压/410 恢复)
- [ ] 双端并发编辑收敛到 State Builder 结果(跨端 E2E)
- [ ] retention 在真实数据下推进 min_snapshot_version 且旧设备 410 → snapshot 恢复成功
- [ ] 一键回滚演练:`TPLANNER_ENABLE_DELTA` 摘除后旧新客户端均回到 snapshot 且无数据差异
- [ ] 密钥与敏感信息审计:cursor 密钥文件与 API key 不进入仓库/APK/Web bundle
