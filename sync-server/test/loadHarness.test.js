import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runLoad } from '../scripts/load-harness.mjs';

// 小规模冒烟:验证压测脚本本身可用、可重复,且大流重放 hash 确定。
// 真实 1k/10k/100k 目标在树莓派上跑 node scripts/load-harness.mjs。
test('load harness smoke run is deterministic across replays', () => {
  const a = runLoad({ n: 300, batchSize: 100 });
  const b = runLoad({ n: 300, batchSize: 100 });

  assert.equal(a.n, 300);
  assert.ok(a.commandsPerSec > 0);
  assert.ok(a.snapshots >= 3, 'every batch with changes produces a snapshot');
  assert.equal(a.receipts, 300);
  assert.equal(a.entities, 15, 'every 20th command creates a task: 15 distinct tasks');
  assert.equal(a.stateHash, b.stateHash, 'replayed stream yields the same canonical state hash');
  assert.ok(a.stateHash.startsWith('sha256:'));
});
