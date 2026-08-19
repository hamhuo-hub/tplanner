// NATS 连接:仅本机 127.0.0.1;凭据来自 deploy/nats.creds(install.sh 生成,user:password 单行)。
import { connect } from '@nats-io/transport-node';
import { readFileSync } from 'node:fs';

const DEFAULT_URL = 'nats://127.0.0.1:4222';

export async function createNatsConnection({
  url = process.env.NATS_URL || DEFAULT_URL,
  credsFile,
} = {}) {
  const opts = { name: 'tplanner-sync' };

  if (credsFile) {
    const line = readFileSync(credsFile, 'utf8').trim();
    const sep = line.indexOf(':');
    if (sep <= 0) throw new Error(`malformed creds file: ${credsFile}`);
    opts.user = line.slice(0, sep);
    opts.pass = line.slice(sep + 1);
  }

  return connect({ servers: [url], ...opts });
}
