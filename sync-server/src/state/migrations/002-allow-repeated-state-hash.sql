-- Coverage snapshots may advance broker_to_sequence without changing business
-- state. state_hash is content identity, not snapshot row identity, so multiple
-- immutable versions are allowed to carry the same state hash.
CREATE TABLE snapshots_v2 (
    version                INTEGER PRIMARY KEY,
    parent_version         INTEGER,
    broker_from_sequence   INTEGER NOT NULL,
    broker_to_sequence     INTEGER NOT NULL,
    schema_version         INTEGER NOT NULL,
    state_hash             TEXT NOT NULL,
    compressed_hash        TEXT NOT NULL,
    compressed_payload     BLOB NOT NULL,
    uncompressed_bytes     INTEGER NOT NULL,
    compressed_bytes       INTEGER NOT NULL,
    created_at             INTEGER NOT NULL
);

INSERT INTO snapshots_v2
SELECT version, parent_version, broker_from_sequence, broker_to_sequence, schema_version,
       state_hash, compressed_hash, compressed_payload, uncompressed_bytes, compressed_bytes,
       created_at
FROM snapshots;

DROP TABLE snapshots;
ALTER TABLE snapshots_v2 RENAME TO snapshots;
