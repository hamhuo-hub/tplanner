// Run with Node 22.15+: node scripts/check-web-sync-queue.mjs (no backend/build).
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { createSerialTaskQueue } from '../src/utils/serialTaskQueue.mjs';
import { createMemoryKvStore } from '../src/syncV3/kvStore.js';

// Production bundlers resolve this existing extensionless dependency. The narrow
// hook lets this check exercise the actual outbox allocator directly in Node.
const hooks = registerHooks({
    resolve(specifier, context, nextResolve) {
        if (specifier === './syncMeta' && context.parentURL?.endsWith('/commandOutbox.js')) {
            return nextResolve('./syncMeta.js', context);
        }
        return nextResolve(specifier, context);
    },
});
const { appendCommands } = await import('../src/syncV3/commandOutbox.js');
hooks.deregister();

const deferred = () => {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
};
const queue = createSerialTaskQueue();
const store = createMemoryKvStore();
await store.set('meta', { deviceId: 'queue-check', nextClientSequence: 1 });
const firstAppended = deferred();
const finishFirstInstall = deferred();
const calls = [];
const first = queue(async () => {
    calls.push('events:start');
    await appendCommands(store, [{ type: 'task.setTitle', aggregateId: 'A', arguments: { title: 'edited' } }]);
    firstAppended.resolve();
    await finishFirstInstall.promise;
    calls.push('events:installed');
    return 'events saved';
});
const second = queue(async () => {
    calls.push('refresh:start');
    await appendCommands(store, [{ type: 'task.setCompleted', aggregateId: 'B', arguments: { completed: true } }]);
    calls.push('refresh:installed');
    return 'refresh saved';
});
await firstAppended.promise;
assert.deepEqual(calls, ['events:start']);
finishFirstInstall.resolve();
assert.deepEqual(await Promise.all([first, second]), ['events saved', 'refresh saved']);
assert.deepEqual(calls, ['events:start', 'events:installed', 'refresh:start', 'refresh:installed']);
const commands = (await store.entries('cmd:')).map(([, command]) => command);
assert.deepEqual(commands.map(command => [command.clientSequence, command.aggregateId]), [[1, 'A'], [2, 'B']]);
assert.equal((await store.get('meta')).nextClientSequence, 3);
console.log('PASS overlapping event/refresh operations retain both actual outbox commands and serialize installation');

const failure = new Error('simulated failed save');
const failed = queue(async () => { throw failure; });
const journal = queue(async () => {
    await appendCommands(store, [{ type: 'journal.setText', aggregateId: '2026-09-08', arguments: { text: 'kept' } }]);
    return 'journal saved';
});
await assert.rejects(failed, error => error === failure);
assert.equal(await journal, 'journal saved');
assert.equal((await store.get('cmd:3')).arguments.text, 'kept');
assert.equal((await store.get('meta')).nextClientSequence, 4);
console.log('PASS a rejected save reaches its caller and does not block the next journal operation');

const synchronousFailure = queue(() => { throw new Error('synchronous failure'); });
const afterThrow = queue(() => 7);
await assert.rejects(synchronousFailure, /synchronous failure/);
assert.equal(await afterThrow, 7);
console.log('PASS synchronous throws do not poison later queue entries');
