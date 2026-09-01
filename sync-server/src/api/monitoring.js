// 健康与状态(见 docs/sync-v3.md §15/§16):
//   /health/live           只证明进程活着
//   /health/ready          证明服务可用:broker 流、SQLite、latest 快照、builder 租约期内
//   /tplanner/v3/status    运行指标:latestVersion、brokerLastSequence、
//                          materializedThroughSequence、queueLag
import { SEQ_MULTIPLIER } from '../sequence.js';
import { SOFTWARE_VERSION } from '../version.js';

export function createMonitoring({ db, jsm, serverInstanceId, now = Date.now }) {
  const latestVersion = () =>
    db.prepare('SELECT version FROM latest_snapshot WHERE singleton_id = 1').get()?.version ?? 0;

  const maxBrokerSequence = () =>
    db.prepare('SELECT COALESCE(MAX(broker_sequence), 0) AS m FROM processed_commands').get().m;

  async function status() {
    let brokerLastSequence = 0;
    let brokerOk = false;
    try {
      const info = await jsm.streams.info('TPLANNER_COMMANDS');
      brokerLastSequence = Number(info.state.last_seq);
      brokerOk = true;
    } catch {
      brokerOk = false;
    }
    const materializedThroughSequence = Math.floor(maxBrokerSequence() / SEQ_MULTIPLIER);
    return {
      softwareVersion: SOFTWARE_VERSION,
      serverInstanceId,
      latestSnapshotVersion: latestVersion(),
      brokerLastSequence,
      materializedThroughSequence,
      queueLag: Math.max(0, brokerLastSequence - materializedThroughSequence),
      brokerOk,
    };
  }

  async function readiness() {
    const checks = {};

    try {
      await jsm.streams.info('TPLANNER_COMMANDS');
      checks.broker = true;
    } catch {
      checks.broker = false;
    }

    try {
      db.prepare('SELECT 1').get();
      checks.database = true;
    } catch {
      checks.database = false;
    }

    checks.latestSnapshot = latestVersion() > 0;

    const lease = db
      .prepare('SELECT lease_expires_at FROM state_builder_lease WHERE singleton_id = 1')
      .get();
    checks.builderLease = Boolean(lease && lease.lease_expires_at >= now());

    return { ok: Object.values(checks).every(Boolean), checks };
  }

  return { status, readiness };
}
