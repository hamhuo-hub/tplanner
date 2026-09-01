import { test } from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
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
  assert.equal(db.pragma('user_version', { simple: true }), 2);
  db.close();
});

test('re-running migrate is idempotent', () => {
  const db = openDatabase(':memory:');
  migrate(db); // second run
  assert.equal(db.pragma('user_version', { simple: true }), 2);
  db.close();
});

test('snapshot versions may repeat a state hash while remaining immutable by version', () => {
  const db = openDatabase(':memory:');
  const insert = db.prepare(`
    INSERT INTO snapshots
      (version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
       state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
       created_at)
    VALUES (?, ?, ?, ?, 3, ?, ?, X'00', 1, 1, ?)
  `);
  insert.run(1, 0, 1, 1, 'sha256:same', 'sha256:compressed-1', 1);
  insert.run(2, 1, 2, 2, 'sha256:same', 'sha256:compressed-2', 2);
  assert.equal(db.prepare('SELECT COUNT(*) AS c FROM snapshots').get().c, 2);
  db.close();
});

test('migration 002 preserves existing snapshots while removing state_hash uniqueness', () => {
  const db = new Database(':memory:');
  db.exec(`
    CREATE TABLE snapshots (
      version INTEGER PRIMARY KEY,
      parent_version INTEGER,
      broker_from_sequence INTEGER NOT NULL,
      broker_to_sequence INTEGER NOT NULL,
      schema_version INTEGER NOT NULL,
      state_hash TEXT NOT NULL UNIQUE,
      compressed_hash TEXT NOT NULL,
      compressed_payload BLOB NOT NULL,
      uncompressed_bytes INTEGER NOT NULL,
      compressed_bytes INTEGER NOT NULL,
      created_at INTEGER NOT NULL
    );
    INSERT INTO snapshots VALUES
      (7, 6, 100, 110, 3, 'sha256:stable', 'sha256:compressed-7', X'07', 1, 1, 7);
    PRAGMA user_version = 1;
  `);

  migrate(db);
  assert.equal(db.pragma('user_version', { simple: true }), 2);
  assert.equal(db.prepare('SELECT broker_to_sequence FROM snapshots WHERE version = 7').get().broker_to_sequence, 110);
  db.prepare(`
    INSERT INTO snapshots VALUES
      (8, 7, 111, 120, 3, 'sha256:stable', 'sha256:compressed-8', X'08', 1, 1, 8)
  `).run();
  assert.equal(db.prepare("SELECT COUNT(*) AS c FROM snapshots WHERE state_hash = 'sha256:stable'").get().c, 2);
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
