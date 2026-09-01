-- Durable, contiguous JetStream checkpoint used for disaster recovery.
--
-- It intentionally starts NULL on an upgraded database: MAX(receipt sequence)
-- is not a continuity proof because empty/redelivered messages have no receipt.
-- State Builder initializes it from the durable consumer's contiguous ack_floor
-- while holding the writer lease, then advances it in each integration DB
-- transaction before the corresponding JetStream messages are ACKed.
CREATE TABLE materializer_progress (
    singleton_id                  INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    materialized_through_sequence INTEGER CHECK (
        materialized_through_sequence IS NULL OR materialized_through_sequence >= 0
    )
);

INSERT INTO materializer_progress (singleton_id, materialized_through_sequence)
VALUES (1, NULL);
