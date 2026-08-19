// 事务发布 outbox 驱动(见 docs/sync-v3.md §9/§10/§18)。
//
// 快照提交后、ready 发布前崩溃 → 重启补发;ready 后、标记前崩溃 → 重复发布,
// NATS 流与客户端按 dedupe key / 版本去重。语义:至少一次传输,业务效果恰好一次。
export const SUBJECT_SNAPSHOT_READY = 'tplanner.v3.snapshot.ready';

export function createOutboxDriver(db, { publish, now = () => Date.now() } = {}) {
  const selectPending = db.prepare(`
    SELECT publication_id, publication_type, dedupe_key, payload_json
    FROM publication_outbox
    WHERE state = 'pending' AND next_attempt_at <= @now
    ORDER BY created_at ASC
    LIMIT 20
  `);
  const markPublished = db.prepare(`
    UPDATE publication_outbox
    SET state = 'published', published_at = @now
    WHERE publication_id = @publicationId
  `);
  const markFailed = db.prepare(`
    UPDATE publication_outbox
    SET attempt_count = attempt_count + 1, next_attempt_at = @nextAttemptAt
    WHERE publication_id = @publicationId
  `);

  /**
   * 补发所有到期 pending 行。发布有序:失败即停并抛错,下次 flush 重试;
   * 版本通知可以跳跃,但 outbox 内顺序发布更省心。
   */
  async function flush() {
    const rows = selectPending.all({ now: now() });
    const published = [];
    for (const row of rows) {
      try {
        const payload = JSON.parse(row.payload_json);
        await publish(row.publication_type, payload, row.dedupe_key);
        markPublished.run({ now: now(), publicationId: row.publication_id });
        published.push(row.publication_id);
      } catch (err) {
        markFailed.run({ nextAttemptAt: now() + 1_000, publicationId: row.publication_id });
        throw err;
      }
    }
    return published;
  }

  function pendingCount() {
    return db
      .prepare("SELECT COUNT(*) AS c FROM publication_outbox WHERE state = 'pending'")
      .get().c;
  }

  return { flush, pendingCount };
}
