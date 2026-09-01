// 单写者租约(见 docs/sync-v3.md §9):抢占 state_builder_lease 成功之后才允许
// attach NATS durable consumer;systemd 单实例 + 本租约兜底 —— 第二个实例
// acquire 失败必须退出,绝不允许两个消费者同时消费命令流。
export function createLease(db, { ttlMs = 30_000 } = {}) {
  const select = db.prepare(
    'SELECT owner_id, lease_expires_at FROM state_builder_lease WHERE singleton_id = 1',
  );
  const insert = db.prepare(`
    INSERT INTO state_builder_lease (singleton_id, owner_id, lease_expires_at)
    VALUES (1, @ownerId, @expiresAt)
  `);
  const update = db.prepare(`
    UPDATE state_builder_lease
    SET owner_id = @ownerId, lease_expires_at = @expiresAt
    WHERE singleton_id = 1 AND (owner_id = @ownerId OR lease_expires_at <= @now)
  `);
  const renew = db.prepare(`
    UPDATE state_builder_lease
    SET lease_expires_at = @expiresAt
    WHERE singleton_id = 1 AND owner_id = @ownerId
  `);
  const release = db.prepare(`
    UPDATE state_builder_lease
    SET lease_expires_at = 0
    WHERE singleton_id = 1 AND owner_id = @ownerId
  `);

  function acquire(ownerId, now = Date.now()) {
    // BEGIN IMMEDIATE:直接拿写锁,避免两个实例同时读到"无主"再同时 INSERT。
    db.exec('BEGIN IMMEDIATE');
    try {
      const row = select.get();
      if (!row) {
        insert.run({ ownerId, expiresAt: now + ttlMs });
        db.exec('COMMIT');
        return { acquired: true, ownerId };
      }
      const result = update.run({ ownerId, expiresAt: now + ttlMs, now });
      db.exec('COMMIT');
      return result.changes > 0
        ? { acquired: true, ownerId }
        : { acquired: false, currentOwner: row.owner_id, leaseExpiresAt: row.lease_expires_at };
    } catch (err) {
      db.exec('ROLLBACK');
      throw err;
    }
  }

  function renewLease(ownerId, now = Date.now()) {
    const result = renew.run({ expiresAt: now + ttlMs, ownerId });
    return result.changes > 0; // false = 已被抢占/接管,应立即停止消费
  }

  function releaseLease(ownerId) {
    return release.run({ ownerId }).changes > 0;
  }

  return { acquire, renewLease, releaseLease };
}
