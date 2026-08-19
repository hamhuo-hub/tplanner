-- V3 权威状态库初始 schema(见 docs/sync-v3.md §9)。
-- PRAGMA(journal_mode/synchronous/foreign_keys/busy_timeout)由 database.js 在连接时设置。

CREATE TABLE processed_commands (
    command_id        TEXT PRIMARY KEY,
    batch_id          TEXT NOT NULL,
    device_id         TEXT NOT NULL,
    client_sequence   INTEGER NOT NULL,
    broker_sequence   INTEGER NOT NULL UNIQUE,
    command_type      TEXT NOT NULL,
    aggregate_id      TEXT,
    status            TEXT NOT NULL,
    error_code        TEXT,
    snapshot_version  INTEGER,
    result_json       TEXT,
    processed_at      INTEGER NOT NULL
);

-- 每设备 client_sequence 永久唯一:重投幂等的第二道防线(JetStream Msg-Id 去重窗口过期后仍有效)
CREATE UNIQUE INDEX processed_commands_device_sequence
    ON processed_commands(device_id, client_sequence);

CREATE TABLE entities (
    entity_type          TEXT NOT NULL,
    entity_id            TEXT NOT NULL,
    lifecycle            TEXT NOT NULL,
    payload_json         TEXT NOT NULL,
    last_broker_sequence INTEGER NOT NULL,
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL,
    deleted_at           INTEGER,
    PRIMARY KEY (entity_type, entity_id)
);

CREATE TABLE snapshots (
    version                INTEGER PRIMARY KEY,
    parent_version         INTEGER,
    broker_from_sequence   INTEGER NOT NULL,
    broker_to_sequence     INTEGER NOT NULL,
    schema_version         INTEGER NOT NULL,
    state_hash             TEXT NOT NULL UNIQUE,
    compressed_hash        TEXT NOT NULL,
    compressed_payload     BLOB NOT NULL,
    uncompressed_bytes     INTEGER NOT NULL,
    compressed_bytes       INTEGER NOT NULL,
    created_at             INTEGER NOT NULL
);

CREATE TABLE latest_snapshot (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    version      INTEGER NOT NULL,
    state_hash   TEXT NOT NULL
);

CREATE TABLE device_progress (
    device_id                  TEXT PRIMARY KEY,
    accepted_client_sequence   INTEGER NOT NULL DEFAULT 0,
    installed_snapshot_version INTEGER NOT NULL DEFAULT 0,
    installed_snapshot_hash    TEXT,
    last_seen_at               INTEGER NOT NULL,
    protocol_version           INTEGER NOT NULL
);

CREATE TABLE publication_outbox (
    publication_id   TEXT PRIMARY KEY,
    publication_type TEXT NOT NULL,
    dedupe_key       TEXT NOT NULL UNIQUE,
    payload_json     TEXT NOT NULL,
    state            TEXT NOT NULL,
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    next_attempt_at  INTEGER NOT NULL DEFAULT 0,
    created_at       INTEGER NOT NULL,
    published_at     INTEGER
);

-- 单写者租约:State Builder 必须先抢占租约再 attach durable consumer(见 docs/sync-v3.md §9)
CREATE TABLE state_builder_lease (
    singleton_id     INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    owner_id         TEXT NOT NULL,
    lease_expires_at INTEGER NOT NULL
);
