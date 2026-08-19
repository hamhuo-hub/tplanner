// JetStream 流定义与幂等创建。业务流只有两条:
//   TPLANNER_COMMANDS      — 唯一全局有序命令流,State Builder 的唯一输入
//   TPLANNER_PUBLICATIONS  — 版本发布与回执通知,API 与客户端只读
import { jetstream, jetstreamManager } from '@nats-io/jetstream';

const DAY_NS = 24 * 60 * 60 * 1_000_000_000;

export const STREAMS = {
  commands: {
    name: 'TPLANNER_COMMANDS',
    subjects: ['tplanner.v3.commands'],
    config: {
      storage: 'file',
      retention: 'limits',
      max_age: 180 * DAY_NS,          // 保留 180 天供恢复重放
      max_msg_size: 256 * 1024,       // 256 KiB
      max_msgs: -1,
      discard: 'old',
      duplicate_window: 2 * 60 * 1_000_000_000, // 2 分钟 Msg-Id 去重窗口
      allow_direct: true,
    },
  },
  publications: {
    name: 'TPLANNER_PUBLICATIONS',
    subjects: ['tplanner.v3.snapshot.ready', 'tplanner.v3.command.receipt'],
    config: {
      storage: 'file',
      retention: 'limits',
      max_age: 30 * DAY_NS,
    },
  },
};

export async function ensureStreams(nc) {
  const jsm = await jetstreamManager(nc);
  for (const { name, subjects, config } of Object.values(STREAMS)) {
    try {
      await jsm.streams.info(name);
    } catch {
      await jsm.streams.add({ name, subjects, ...config });
    }
  }
  return jetstream(nc);
}
