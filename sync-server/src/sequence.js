// 命令级 broker_sequence 的派生规则(见 src/materializer/main.js):
//   broker_sequence = 批次 MQ 序列 × SEQ_MULTIPLIER + 批内序号
// 全局唯一、有序、重投确定;每批命令数远小于 SEQ_MULTIPLIER。
// API 的状态端点据此从 processed_commands 反推 materializedThroughSequence。
export const SEQ_MULTIPLIER = 1_000_000;
