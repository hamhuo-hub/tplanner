import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createCommandPublisher, SUBJECT_COMMANDS } from '../src/broker/publisher.js';

const batch = {
  protocolVersion: 3,
  batchId: '0198f2a1-3c4b-7d5e-8f90-1234567890ab',
  deviceId: 'phone-0198f200',
  firstClientSequence: 1,
  lastClientSequence: 1,
  commands: [],
};

function stubJetStream(ack) {
  const calls = [];
  return {
    calls,
    js: {
      publish: async (subject, payload, opts) => {
        calls.push({ subject, payload, opts });
        return ack;
      },
    },
  };
}

test('publishes the batch to the commands subject with Msg-Id dedupe', async () => {
  const { calls, js } = stubJetStream({ seq: 10321, duplicate: false });
  const publisher = createCommandPublisher(js);

  const result = await publisher.publish(batch);

  assert.equal(calls.length, 1);
  assert.equal(calls[0].subject, SUBJECT_COMMANDS);
  assert.equal(calls[0].opts.msgID, batch.batchId);
  assert.deepEqual(JSON.parse(calls[0].payload), batch);
  assert.deepEqual(result, {
    batchId: batch.batchId,
    brokerSequence: 10321,
    state: 'BROKER_PERSISTED',
    duplicate: false,
  });
});

test('duplicate ack still resolves to the original broker sequence', async () => {
  const { js } = stubJetStream({ seq: 10321, duplicate: true });
  const publisher = createCommandPublisher(js);

  const result = await publisher.publish(batch);

  assert.equal(result.brokerSequence, 10321);
  assert.equal(result.duplicate, true);
  assert.equal(result.state, 'BROKER_PERSISTED');
});

test('publish timeout is configurable', async () => {
  const { calls, js } = stubJetStream({ seq: 1, duplicate: false });
  const publisher = createCommandPublisher(js, { publishTimeoutMs: 1234 });

  await publisher.publish(batch);

  assert.equal(calls[0].opts.timeout, 1234);
});
