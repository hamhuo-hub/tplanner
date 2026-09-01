#!/usr/bin/env node
// Run only during a maintenance window with tplanner-sync-api and
// tplanner-state-builder stopped. The migration and its publication snapshot
// are committed atomically and repeat runs are no-ops.
import { openDatabase } from '../src/state/database.js';
import { resolveServerInstanceId } from '../src/serverInstance.js';
import { migrateCanonicalTaskEntities } from '../src/state/canonicalTaskMigration.js';

const dbPath = process.env.TPLANNER_DB_PATH || '/var/lib/tplanner-sync/state/tplanner.db';
const waitForLeaseMs = Number(process.env.TPLANNER_WAIT_FOR_LEASE_MS || 0);
const db = openDatabase(dbPath);

try {
  const findLiveLease = db.prepare(`
    SELECT owner_id, lease_expires_at
      FROM state_builder_lease
     WHERE singleton_id = 1 AND lease_expires_at >= ?
  `);
  const deadline = Date.now() + (Number.isFinite(waitForLeaseMs) ? Math.max(0, waitForLeaseMs) : 0);
  let liveLease = findLiveLease.get(Date.now());
  while (liveLease && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, Math.min(250, deadline - Date.now())));
    liveLease = findLiveLease.get(Date.now());
  }
  if (liveLease) {
    throw new Error(
      `state builder lease is still active (${liveLease.owner_id}); stop services and wait for lease expiry`,
    );
  }

  const result = migrateCanonicalTaskEntities(db, {
    serverInstanceId: resolveServerInstanceId(dbPath),
  });
  console.log(JSON.stringify({ dbPath, ...result }));
} finally {
  db.close();
}
