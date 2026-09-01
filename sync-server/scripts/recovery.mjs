#!/usr/bin/env node
// Explicit, composable recovery operations used by deploy/restore.sh. This
// file never swaps live database paths or starts/stops systemd services.
import Database from 'better-sqlite3';
import { jetstreamManager } from '@nats-io/jetstream';
import { createNatsConnection } from '../src/broker/natsConnection.js';
import { openDatabase } from '../src/state/database.js';
import {
  readRecoveryWatermarks,
  verifyAndStageCompressedBackup,
} from '../src/state/backup.js';
import {
  assessJetStreamReplayWindow,
  publishRecoverySnapshot,
} from '../src/state/recovery.js';
import { resolveServerInstanceId } from '../src/serverInstance.js';

const COMMANDS_STREAM = 'TPLANNER_COMMANDS';
const BUILDER_CONSUMER = 'state-builder';

function parseOptions(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name?.startsWith('--') || value === undefined) {
      throw new Error(`expected --name value, got: ${argv.slice(index).join(' ')}`);
    }
    if (Object.hasOwn(options, name)) throw new Error(`duplicate option: ${name}`);
    options[name] = value;
  }
  return options;
}

function required(options, name) {
  const value = options[name];
  if (typeof value !== 'string' || value === '') throw new Error(`missing required option: ${name}`);
  return value;
}

function nonNegativeInteger(raw, name) {
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${name} must be a non-negative safe integer`);
  }
  return value;
}

async function withNats(fn) {
  const nc = await createNatsConnection({ credsFile: process.env.NATS_CREDS_FILE });
  try {
    return await fn(await jetstreamManager(nc));
  } finally {
    await nc.close();
  }
}

function isConsumerNotFound(error) {
  return error?.code === '404'
    || error?.api_error?.code === 404
    || error?.api_error?.err_code === 10014;
}

async function verifyReplayWindow(dbPath) {
  const db = new Database(dbPath, { readonly: true, fileMustExist: true });
  let watermarks;
  try {
    watermarks = readRecoveryWatermarks(db);
  } finally {
    db.close();
  }
  if (watermarks.materializedThroughSequence == null) {
    throw new Error('restored database has no processed_commands MQ watermark');
  }

  return withNats(async (jsm) => {
    const info = await jsm.streams.info(COMMANDS_STREAM);
    const firstSequence = Number(info.state.first_seq);
    const lastSequence = Number(info.state.last_seq);
    const assessment = assessJetStreamReplayWindow({
      firstSequence,
      lastSequence,
      materializedThroughSequence: watermarks.materializedThroughSequence,
    });
    const result = { ...watermarks, firstSequence, lastSequence, ...assessment };
    if (!assessment.safe) {
      throw new Error(`JetStream cannot replay restored database: ${JSON.stringify(result)}`);
    }
    return result;
  });
}

async function deleteBuilderConsumer() {
  return withNats(async (jsm) => {
    try {
      await jsm.consumers.info(COMMANDS_STREAM, BUILDER_CONSUMER);
    } catch (error) {
      if (isConsumerNotFound(error)) return { deleted: false, alreadyAbsent: true };
      throw error;
    }
    const deleted = await jsm.consumers.delete(COMMANDS_STREAM, BUILDER_CONSUMER);
    if (!deleted) throw new Error('JetStream did not confirm state-builder consumer deletion');
    return { deleted: true, alreadyAbsent: false };
  });
}

async function builderDrainStatus() {
  return withNats(async (jsm) => {
    const info = await jsm.consumers.info(COMMANDS_STREAM, BUILDER_CONSUMER);
    const numPending = Number(info.num_pending);
    const numAckPending = Number(info.num_ack_pending);
    return {
      drained: numPending === 0 && numAckPending === 0,
      numPending,
      numAckPending,
    };
  });
}

async function waitForExpiredLease(db, waitMs) {
  const findLiveLease = db.prepare(`
    SELECT owner_id, lease_expires_at
      FROM state_builder_lease
     WHERE singleton_id = 1 AND lease_expires_at > ?
  `);
  const deadline = Date.now() + waitMs;
  let live = findLiveLease.get(Date.now());
  while (live && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, Math.min(250, deadline - Date.now())));
    live = findLiveLease.get(Date.now());
  }
  if (live) {
    throw new Error(
      `state builder lease is still active (${live.owner_id}); do not clear it; wait for expiry`,
    );
  }
}

async function main(argv) {
  const [command, ...rawOptions] = argv;
  const options = parseOptions(rawOptions);

  if (command === 'verify-backup') {
    return verifyAndStageCompressedBackup({
      gzipPath: required(options, '--archive'),
      manifestPath: required(options, '--manifest'),
      stagedDbPath: required(options, '--staged-db'),
    });
  }
  if (command === 'verify-replay-window') {
    return verifyReplayWindow(required(options, '--db'));
  }
  if (command === 'delete-builder-consumer') {
    return deleteBuilderConsumer();
  }
  if (command === 'builder-drain-status') {
    const result = await builderDrainStatus();
    if (!result.drained) process.exitCode = 2;
    return result;
  }
  if (command === 'publish-snapshot') {
    const dbPath = required(options, '--db');
    const preRestoreHighWater = nonNegativeInteger(
      required(options, '--pre-restore-high-water'),
      '--pre-restore-high-water',
    );
    const waitMs = nonNegativeInteger(options['--wait-for-lease-ms'] ?? '0', '--wait-for-lease-ms');
    const db = openDatabase(dbPath);
    try {
      await waitForExpiredLease(db, waitMs);
      return publishRecoverySnapshot(db, {
        preRestoreHighWater,
        serverInstanceId: resolveServerInstanceId(dbPath),
      });
    } finally {
      db.close();
    }
  }

  throw new Error(
    'usage: recovery.mjs verify-backup|verify-replay-window|delete-builder-consumer|builder-drain-status|publish-snapshot [options]',
  );
}

main(process.argv.slice(2)).then(
  (result) => console.log(JSON.stringify(result)),
  (error) => {
    console.error(error.message);
    process.exitCode = 1;
  },
);
