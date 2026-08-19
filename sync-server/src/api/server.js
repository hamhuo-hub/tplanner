// V3 API 入口:开 SQLite → 连 NATS → 确保流存在 → 挂载全部端点。
// serverInstanceId 与 State Builder 用同一解析规则(见 ../serverInstance.js)。
import { jetstreamManager } from '@nats-io/jetstream';
import { buildServer } from './app.js';
import { createMonitoring } from './monitoring.js';
import { createStore } from './store.js';
import { openDatabase } from '../state/database.js';
import { createNatsConnection } from '../broker/natsConnection.js';
import { ensureStreams } from '../broker/streams.js';
import { createCommandPublisher } from '../broker/publisher.js';
import { loadBatchValidator } from './validation.js';
import { createLegacyAdapter } from './legacyCompat.js';
import { resolveServerInstanceId } from '../serverInstance.js';

const PORT = Number(process.env.PORT || 37401);
const CREDS_FILE = process.env.NATS_CREDS_FILE; // systemd 单元注入
const DB_PATH = process.env.TPLANNER_DB_PATH || '/var/lib/tplanner-sync/state/tplanner.db';

const db = openDatabase(DB_PATH);
const nc = await createNatsConnection({ credsFile: CREDS_FILE });
const jsm = jetstreamManager(nc);
const js = await ensureStreams(nc);
const publisher = createCommandPublisher(js);
const validateBatch = await loadBatchValidator();
const serverInstanceId = resolveServerInstanceId(DB_PATH);
const store = createStore(db, { serverInstanceId });
const health = createMonitoring({ db, jsm, serverInstanceId });
const legacy = createLegacyAdapter({
    db,
    publisher,
    // 过渡期终点(§21):连续 7 天无 V1 PUT 后由 cutover.sh 置 1,旧客户端写入返回 410
    writesDisabled: process.env.TPLANNER_DISABLE_V1_WRITES === '1',
});

const app = buildServer({ publisher, validateBatch, store, health, legacy });
await app.listen({ port: PORT, host: '127.0.0.1' });
console.log(`tplanner-sync-api listening on 127.0.0.1:${PORT}`);
