import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openDatabase, migrate } from '../src/state/database.js';

function tableNames(db) {
  return db
    .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
    .all()
    .map((r) => r.name);
}

test('creates the full V3 schema on first open', () => {
  const db = openDatabase(':memory:');
  const tables = tableNames(db);
  for (const t of [
    'processed_commands',
    'entities',
    'snapshots',
    'latest_snapshot',
    'device_progress',
    'publication_outbox',
    'state_builder_lease',
  ]) {
    assert.ok(tables.includes(t), `missing table ${t}`);
  }
  assert.equal(db.pragma('user_version', { simple: true }), 1);
  db.close();
});

test('re-running migrate is idempotent', () => {
  const db = openDatabase(':memory:');
  migrate(db); // second run
  assert.equal(db.pragma('user_version', { simple: true }), 1);
  db.close();
});

test('duplicate command_id is rejected', () => {
  const db = openDatabase(':memory:');
  const insert = db.prepare(`
    INSERT INTO processed_commands
      (command_id, batch_id, device_id, client_sequence, broker_sequence, command_type, status, processed_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `);
  insert.run('cmd-1', 'batch-1', 'dev-1', 1, 100, 'task.create', 'APPLIED', 0);
  assert.throws(() =>
    insert.run('cmd-1', 'batch-2', 'dev-1', 2, 101, 'task.create', 'APPLIED', 0)
  );
  db.close();
});

test('duplicate (device_id, client_sequence) is rejected', () => {
  const db = openDatabase(':memory:');
  const insert = db.prepare(`
    INSERT INTO processed_commands
      (command_id, batch_id, device_id, client_sequence, broker_sequence, command_type, status, processed_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `);
  insert.run('cmd-1', 'batch-1', 'dev-1', 1, 100, 'task.create', 'APPLIED', 0);
  assert.throws(() =>
    insert.run('cmd-2', 'batch-1', 'dev-1', 1, 101, 'task.create', 'APPLIED', 0)
  );
  db.close();
});

test('lease table enforces a single row', () => {
  const db = openDatabase(':memory:');
  const insert = db.prepare(
    'INSERT INTO state_builder_lease (singleton_id, owner_id, lease_expires_at) VALUES (1, ?, ?)'
  );
  insert.run('builder-a', 1);
  assert.throws(() => insert.run('builder-b', 2));
  db.close();
});
