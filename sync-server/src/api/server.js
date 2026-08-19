// V3 API 入口:连 NATS → 确保流存在 → 挂载命令批次端点。
// 快照/回执/通知端点随后续提交加入。
import { buildServer } from './app.js';
import { createNatsConnection } from '../broker/natsConnection.js';
import { ensureStreams } from '../broker/streams.js';
import { createCommandPublisher } from '../broker/publisher.js';
import { loadBatchValidator } from './validation.js';

const PORT = Number(process.env.PORT || 37401);
const CREDS_FILE = process.env.NATS_CREDS_FILE; // systemd 单元注入

const nc = await createNatsConnection({ credsFile: CREDS_FILE });
const js = await ensureStreams(nc);
const publisher = createCommandPublisher(js);
const validateBatch = await loadBatchValidator();

const app = buildServer({ publisher, validateBatch });
await app.listen({ port: PORT, host: '127.0.0.1' });
console.log(`tplanner-sync-api listening on 127.0.0.1:${PORT}`);
