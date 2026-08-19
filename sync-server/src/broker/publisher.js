// 命令发布器:以 batchId 作为 Msg-Id 保证 broker 侧去重。
// 刻意不 import nats —— js(JetStream 上下文)由调用方注入,便于无依赖单元测试。
export const SUBJECT_COMMANDS = 'tplanner.v3.commands';

export function createCommandPublisher(js, { publishTimeoutMs = 10_000 } = {}) {
  return {
    /**
     * 持久发布一个命令批次。
     * JetStream 以 Msg-Id 去重:重复 batchId 返回原消息的 ack(duplicate=true),
     * 因此重试安全 —— 返回的 brokerSequence 始终是首次发布的全局序列。
     */
    async publish(batch) {
      const ack = await js.publish(SUBJECT_COMMANDS, JSON.stringify(batch), {
        msgID: batch.batchId,
        timeout: publishTimeoutMs,
      });
      return {
        batchId: batch.batchId,
        brokerSequence: Number(ack.seq),
        state: 'BROKER_PERSISTED',
        duplicate: Boolean(ack.duplicate),
      };
    },
  };
}
