// serverInstanceId(见 docs/sync-v3.md §11):标识一个"数据宇宙"。
// API 与 State Builder 两个进程必须读到同一个值,且进程重启不能变化 ——
// 默认由 DB 路径派生(同部署同路径 ⇒ 同 ID),部署可用
// TPLANNER_SERVER_INSTANCE_ID 显式覆盖(install.sh 生成并注入两个 systemd 单元)。
import { createHash } from 'node:crypto';

export function resolveServerInstanceId(dbPath, envId = process.env.TPLANNER_SERVER_INSTANCE_ID) {
  if (envId && envId !== '') return envId;
  return `srv-${createHash('sha256').update(dbPath).digest('hex').slice(0, 16)}`;
}
