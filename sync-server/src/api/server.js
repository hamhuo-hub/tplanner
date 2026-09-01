// V3 API 入口:开 SQLite → 连 NATS → 确保流存在 → 挂载全部端点。
// serverInstanceId 与 State Builder 用同一解析规则(见 ../serverInstance.js)。
import { jetstreamManager } from '@nats-io/jetstream';
import { buildServer } from './app.js';
import { createChangesService } from './changes.js';
import { createMetrics } from './metrics.js';
import { createMonitoring } from './monitoring.js';
import { createStore } from './store.js';
import { openDatabase } from '../state/database.js';
import { createNatsConnection } from '../broker/natsConnection.js';
import { ensureStreams } from '../broker/streams.js';
import { createCommandPublisher } from '../broker/publisher.js';
import { loadBatchValidator } from './validation.js';
import { resolveServerInstanceId } from '../serverInstance.js';
import { resolveCursorSecret } from '../state/cursor.js';

const PORT = Number(process.env.PORT || 37401);
const CREDS_FILE = process.env.NATS_CREDS_FILE; // systemd 单元注入
const DB_PATH = process.env.TPLANNER_DB_PATH || '/var/lib/tplanner-sync/state/tplanner.db';

const db = openDatabase(DB_PATH);
const nc = await createNatsConnection({ credsFile: CREDS_FILE });
const jsm = await jetstreamManager(nc);
const js = await ensureStreams(nc);
const publisher = createCommandPublisher(js);
const validateBatch = await loadBatchValidator();
const serverInstanceId = resolveServerInstanceId(DB_PATH);
const store = createStore(db, { serverInstanceId });

// delta-v1 默认关闭:capabilities.downlinkModes = ["snapshot"],rollback 只需
// 去掉 TPLANNER_ENABLE_DELTA=1(数据库里的 journal 继续保留,无需 down migration)。
const metrics = createMetrics();
const changes = createChangesService({
  db,
  serverInstanceId,
  cursorSecret: resolveCursorSecret(DB_PATH),
  enabled: process.env.TPLANNER_ENABLE_DELTA === '1',
  metrics,
  retention: {
    keepCommits: Number(process.env.TPLANNER_JOURNAL_MAX_COMMITS) || 100_000,
    keepAgeMs: (Number(process.env.TPLANNER_JOURNAL_MAX_AGE_DAYS) || 30) * 24 * 3_600_000,
  },
});
const health = createMonitoring({ db, jsm, serverInstanceId, metrics });

const app = buildServer({
  publisher,
  validateBatch,
  store,
  health,
  changes,
  logger: {
    level: process.env.LOG_LEVEL || 'info',
    redact: {
      paths: ['req.headers.authorization'],
      censor: '[REDACTED]',
    },
  },
});
await app.listen({ port: PORT, host: '127.0.0.1' });
app.log.info({ port: PORT, host: '127.0.0.1' }, 'tplanner-sync-api listening');
