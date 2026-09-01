function requireSequence(value, name) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${name} must be a non-negative safe integer`);
  }
}

export function readMaterializedThroughSequence(db) {
  const row = db.prepare(`
    SELECT materialized_through_sequence AS sequence
      FROM materializer_progress
     WHERE singleton_id = 1
  `).get();
  if (!row) throw new Error('materializer_progress singleton row is missing');
  return row.sequence == null ? null : Number(row.sequence);
}

/**
 * One-time upgrade bridge. The durable ack floor is the broker's proof that
 * every preceding message was acknowledged only after its SQLite transaction.
 * Never move a previously persisted checkpoint backwards or silently accept a
 * broker cursor ahead of it.
 */
export function initializeMaterializerProgress(
  db,
  {
    durableAckFloor,
    assertWriterLease = () => {},
  },
) {
  requireSequence(durableAckFloor, 'durableAckFloor');
  return db.transaction(() => {
    assertWriterLease();
    const current = readMaterializedThroughSequence(db);
    if (current == null) {
      db.prepare(`
        UPDATE materializer_progress
           SET materialized_through_sequence = ?
         WHERE singleton_id = 1 AND materialized_through_sequence IS NULL
      `).run(durableAckFloor);
      return { initialized: true, materializedThroughSequence: durableAckFloor };
    }
    if (durableAckFloor > current) {
      throw new Error(
        `JetStream durable ack floor ${durableAckFloor} is ahead of SQLite checkpoint ${current}`,
      );
    }
    return { initialized: false, materializedThroughSequence: current };
  })();
}

/** Advance the contiguous checkpoint inside the caller's integration tx. */
export function advanceMaterializerProgress(db, messageSequences) {
  const sequences = [...new Set(messageSequences)].sort((a, b) => a - b);
  for (const sequence of sequences) requireSequence(sequence, 'messageSequence');

  const current = readMaterializedThroughSequence(db);
  if (current == null) throw new Error('materializer progress has not been initialized');

  let next = current;
  for (const sequence of sequences) {
    if (sequence <= next) continue; // redelivery after DB commit but before broker ACK
    if (sequence !== next + 1) {
      throw new Error(`non-contiguous JetStream delivery: expected ${next + 1}, got ${sequence}`);
    }
    next = sequence;
  }
  if (next !== current) {
    db.prepare(`
      UPDATE materializer_progress
         SET materialized_through_sequence = ?
       WHERE singleton_id = 1 AND materialized_through_sequence = ?
    `).run(next, current);
  }
  return next;
}
