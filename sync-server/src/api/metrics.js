// 极简进程内 telemetry(见 docs/sync-v3.md §9.4/§16)。
//
// 单进程、重启清零 —— 只服务于 /tplanner/v3/status 的实时观测与 P0 告警,
// 不是长期时序存储。生产长时序仍走日志聚合。
export function createMetrics() {
  const counters = new Map();
  const gauges = new Map();

  return {
    increment(name, value = 1) {
      counters.set(name, (counters.get(name) ?? 0) + value);
    },
    setGauge(name, value) {
      gauges.set(name, value);
    },
    snapshot() {
      return {
        counters: Object.fromEntries([...counters].sort(([a], [b]) => a.localeCompare(b))),
        gauges: Object.fromEntries([...gauges].sort(([a], [b]) => a.localeCompare(b))),
      };
    },
  };
}
