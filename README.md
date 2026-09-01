# tPlanner Sync Server 8.0.0

This branch is the standalone Sync V3 server. Version **8.0.0** permanently
retires the dataset PUT/merge protocol: clients upload semantic commands and
install immutable snapshots produced by one State Builder.

`softwareVersion` is `8.0.0`; `protocolVersion` and `schemaVersion` remain `3`.
The service exposes no V1 data routes. `GET /health` is retained only as an
alias of V3 readiness for infrastructure probes.

## Runtime

The production topology has three processes and one persistent data root:

```text
HTTPS reverse proxy
  -> Fastify API (127.0.0.1:37401)
       -> NATS JetStream (127.0.0.1:4222)
            -> single State Builder
                 -> /var/lib/tplanner-sync/state/tplanner.db
```

The State Builder uses the JetStream sequence as the global order and holds a
SQLite writer lease. It closes an ordinary integration batch after 100 ms of
quiet, with a five-second forced ceiling for sustained traffic. Snapshot,
receipts, entity changes, and publication outbox are committed atomically.
Lease renewal starts immediately after acquisition; startup recovery and every
authoritative SQLite transaction synchronously fence on the current owner and
expiry, so an expired process cannot commit after another builder takes over.

A snapshot version also proves immutable receipt coverage. Every newly decided
terminal receipt publishes a version whose `brokerToSequence` covers it, even
when the canonical `stateHash` is unchanged; pure `SEQUENCE_GAP` receipts and
broker redelivery of already processed commands do not create versions. On
startup, a terminal receipt beyond the latest watermark is repaired by one
idempotent coverage snapshot and its missing `snapshotVersion` is backfilled in
the same transaction.

## HTTP contract

All sync routes use the `/tplanner/v3` prefix:

- `GET /capabilities` reports software/protocol/schema versions and limits.
- `POST /command-batches` persists a validated idempotent batch to JetStream.
- `GET /receipts` reports terminal command results by device sequence.
- `GET /snapshots/latest` returns only the JSON manifest.
- `GET /snapshots/:version` returns the original gzip bytes with
  `Content-Type: application/gzip` and no `Content-Encoding` header.
- `GET /notifications` long-polls the latest snapshot version/hash.
- `POST /devices/:deviceId/snapshot-acks` records atomic installation.
- `GET /status`, `/health/live`, `/health/ready`, and `/health` expose
  structured operational state.

CORS permits `https://plan.hamhuo.top`, localhost/127.0.0.1 development
origins, and Electron's `null`/`file://` origins. Authorization and command
idempotency headers are accepted; snapshot ETag/version/hash headers are
exposed.

## Canonical task data

V3 task state formally owns checklist `title`, custom lists and `listId`,
recurrence `{frequency,count}`, alarm `{enabled,offsetMinutes}`, `colorId`,
location `{lat,lng}`, and the forward-compatible `extras` object. Supported
commands include:

```text
task.setAppearance  task.setAlarm       task.setLocation
task.setExtras      task.setRecurrence  task.assignList
checklist.*         list.create/rename/setColor/delete
```

Every task snapshot contains the complete canonical field set; default/null/
empty values are explicit rather than represented by an absent property.

The one-shot migration converts stored checklist `text` to `title`, root
`start/end` to `schedule {startAt,endAt}`, root `type` to `itemType`, and lifts
historical recurrence/alarm/location fields. Timezone, every unknown field and
existing `extras` are retained. It is idempotent and publishes a new immutable
snapshot inside the same SQLite transaction.

## Local verification

Node.js 20 or newer is required.

```bash
cd sync-server
npm ci
npm test
```

## Production deployment runbook

This repository change does **not** deploy itself. Perform the following in a
maintenance window, from a reviewed release checkout. `/opt/tplanner-sync/nats-server`
must already contain the pinned NATS binary.

1. Upload the release checkout to a staging directory on the server.
2. Run `sudo bash sync-server/deploy/install.sh`. The installer stops API,
   State Builder, NATS and the maintenance timers, removes the retired all-in-one systemd
   unit, preserves the NATS credential and persistent data, replaces
   only `/opt/tplanner-sync/{sync-server,sync-v3}`, and installs dependencies.
   While every writer remains stopped it automatically creates and verifies a
   pre-migration backup, waits up to 35 seconds for the prior builder lease,
   performs the idempotent canonical-task migration, then installs systemd
   units. Any backup or migration failure aborts with services still stopped.
3. Review the printed backup manifest and migration result. The installer
   deliberately does not start the application.
4. Start in order: `nats-server`, `tplanner-state-builder`, then
   `tplanner-sync-api`. Start the health and backup timers last.
5. Verify `/health/ready`, `/tplanner/v3/capabilities`, manifest download,
   gzip byte/hash validation, an idempotent test command and its receipt.
   Confirm retired `/tplanner/events`, `/tplanner/changes`, and other dataset
   routes return 404.

Hourly backups use SQLite's Online Backup API, run `integrity_check`, gzip the
copy, and write a SHA-256 manifest last as the completion marker. Retention is
all hourly copies for 24 hours, then the newest copy per UTC day for 30 days.
The one-minute watchdog checks end-to-end readiness and performs one bounded
stack restart before reporting failure.

Never restore only the SQLite file while a writer is running. Stop API and
State Builder, verify the selected manifest hash and SQLite integrity, then
stop NATS and restore the database. Before restarting State Builder, reset its
`state-builder` durable consumer so the retained `TPLANNER_COMMANDS` stream is
replayed from its beginning; restored receipts make earlier commands
idempotent and commands newer than the backup are re-applied. Starting against
the old, ahead-of-database consumer cursor would skip those commands. The
hourly job backs up SQLite and private configuration, not live JetStream files;
copy completed artifacts off-host. Client outboxes remain the final source for
commands that were never acknowledged as broker-persisted.
