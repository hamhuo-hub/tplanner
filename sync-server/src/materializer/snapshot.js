// 不可变快照生成(见 docs/sync-v3.md §7)。
//
// stateHash 只覆盖 RFC 8785 (JCS) canonical 化的 `state` 对象;信封元数据
// (createdAt、serverInstanceId、broker 序列)不参与 —— 同一条命令流重放任意次
// hash 一致。compressedHash 覆盖 gzip 载荷,用作下载校验与 ETag。
import { createHash } from 'node:crypto';
import { gzipSync } from 'node:zlib';
import canonicalize from 'canonicalize';

export function sha256Hex(input) {
  return createHash('sha256').update(input).digest('hex');
}

export function canonicalStateHash(state) {
  const canonical = canonicalize(state);
  if (canonical === undefined) {
    throw new Error('state is not canonicalizable: contains non-JSON values');
  }
  return `sha256:${sha256Hex(canonical)}`;
}

export function buildSnapshot({
  state,
  snapshotVersion,
  parentVersion,
  serverInstanceId,
  brokerFromSequence,
  brokerToSequence,
  createdAt = new Date().toISOString(),
}) {
  const envelope = {
    snapshotSchemaVersion: 3,
    snapshotVersion,
    parentVersion,
    serverInstanceId,
    brokerFromSequence,
    brokerToSequence,
    createdAt,
    state,
  };
  const stateHash = canonicalStateHash(state);
  const uncompressed = Buffer.from(JSON.stringify(envelope), 'utf8');
  const compressed = gzipSync(uncompressed);
  const compressedHash = `sha256:${sha256Hex(compressed)}`;
  const manifest = {
    snapshotVersion,
    parentVersion,
    schemaVersion: 3,
    stateHash,
    compressedHash,
    encoding: 'gzip',
    compressedBytes: compressed.length,
    uncompressedBytes: uncompressed.length,
    serverInstanceId,
  };
  return { envelope, manifest, stateHash, compressedHash, compressed };
}
