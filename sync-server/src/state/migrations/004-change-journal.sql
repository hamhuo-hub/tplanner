-- V4 change journal(增量下行的服务端一半,见 docs/sync-v3.md §9.1)。
--
-- journal commit identity == snapshotVersion:每次 State Builder 产生新快照的
-- 同一事务也产生一个 journal commit;NOOP/REJECTED coverage commit 可以携带
-- 零条 change(changes=[] 不得省略)。不再另造全局 journal sequence。
--
-- 部署当刻 head 之前的快照不回填:min_snapshot_version 记录 journal 覆盖
-- 起点,更早的 cursor 一律走完整快照。expand-only,与旧版 State Builder 兼容
-- (旧版不写这些表,重升级前由运维 bump journalEpoch 或关闭 delta)。

CREATE TABLE sync_journal_meta (
    singleton_id         INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    journal_epoch        TEXT NOT NULL,
    min_snapshot_version INTEGER NOT NULL,
    created_at           INTEGER NOT NULL
);

-- 迁移时 journal 从当前快照 head 起步(没有 head 的新库为 0,含 bootstrap)。
INSERT INTO sync_journal_meta (singleton_id, journal_epoch, min_snapshot_version, created_at)
SELECT 1,
       'j-' || strftime('%Y%m%d', 'now') || '-a',
       COALESCE((SELECT MAX(version) FROM snapshots), 0),
       CAST(strftime('%s', 'now') AS INTEGER);

CREATE TABLE change_commits (
    snapshot_version      INTEGER PRIMARY KEY,
    parent_version        INTEGER NOT NULL,
    broker_from_sequence  INTEGER NOT NULL,
    broker_to_sequence    INTEGER NOT NULL,
    schema_version        INTEGER NOT NULL,
    state_hash_after      TEXT NOT NULL,
    change_count          INTEGER NOT NULL,
    payload_bytes         INTEGER NOT NULL DEFAULT 0,
    created_at            INTEGER NOT NULL,

    FOREIGN KEY (snapshot_version)
        REFERENCES snapshots(version)
);

CREATE TABLE change_items (
    snapshot_version       INTEGER NOT NULL,
    ordinal                INTEGER NOT NULL,
    change_type            TEXT NOT NULL,
    entity_type            TEXT NOT NULL,
    entity_id              TEXT NOT NULL,
    entity_broker_sequence INTEGER NOT NULL,
    payload_json           TEXT NOT NULL,

    PRIMARY KEY (snapshot_version, ordinal),

    FOREIGN KEY (snapshot_version)
        REFERENCES change_commits(snapshot_version)
        ON DELETE CASCADE
);

CREATE INDEX change_items_entity_idx
    ON change_items (entity_type, entity_id, snapshot_version);
