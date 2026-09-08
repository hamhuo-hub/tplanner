// Run with: node scripts/check-recurring-series.mjs (no test framework or backend).
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
    buildRecurringEdit, detachFromSeries, expandSharedContentUpdates, deleteRecurringOccurrences,
    legacyAndroidOccurrenceId, occurrenceAt, recoverLegacySeries, scheduleFromEditor,
} from '../src/domain/recurringTasks.mjs';
import { selectNextPendingOccurrences } from '../src/domain/recurringTaskSelection.mjs';
import { diffEventsToCommands, toUiEvents } from '../src/syncV3/commandsFromData.js';
import { emptyState, applyCommand } from '../src/syncV3/localReducer.js';
import { toDatabaseEvent, eventSchema } from '../src/database/schema.js';

let checks = 0;
const check = (name, run) => { run(); checks++; console.log(`PASS ${name}`); };
const base = {
    id: '00000000-0000-4000-8000-000000000001', type: 'task', title: '读书',
    start: new Date('2026-01-31T14:15:12.345Z'), end: new Date('2026-01-31T15:15:12.345Z'),
    timezone: 'America/New_York', recurrenceType: 'monthly', recurrenceCount: 3,
    note: '原备注', colorId: 2, completed: false,
    checklist: [{ id: 'chapter', text: '第一章', completed: false }], extras: { futureExtra: 'keep' },
};
const series = buildRecurringEdit([], null, base, 100);
const dates = events => events.map(event => new Date(event.start).toISOString());

check('new series has stable IDs, anchors and a real occurrence per date', () => {
    assert.equal(series.length, 3);
    assert.equal(series[0].id, base.id);
    assert.deepEqual(series.map(e => e.id), buildRecurringEdit([], null, base, 100).map(e => e.id));
    assert.deepEqual(series.map(e => e.id), buildRecurringEdit(series, null, base, 100).map(e => e.id)); // Retry after outbox commit.
    assert.deepEqual(dates(series), ['2026-01-31T14:15:12.345Z', '2026-02-28T14:15:12.345Z', '2026-03-31T13:15:12.345Z']);
    assert(series.every((e, index) => e.recurrence.seriesId === base.id && e.recurrence.occurrenceIndex === index));
});
check('existing standalone task becomes a series without replacing its ID', () => {
    const standalone = detachFromSeries(base);
    const updates = buildRecurringEdit([standalone], standalone, { ...standalone, recurrenceType: 'daily', recurrenceCount: 2 });
    assert.equal(updates.length, 2);
    assert.equal(updates[0].id, standalone.id);
});
check('unbound historical random IDs are not expanded by ordinary editing', () => {
    const legacy = { ...base, recurrenceType: 'daily', recurrenceCount: 50 };
    assert.equal(buildRecurringEdit([legacy], legacy, { ...legacy, title: '改名' }).length, 1);
    assert.equal(buildRecurringEdit([legacy], legacy, legacy).length, 1);
    assert.equal(buildRecurringEdit([legacy], legacy, { ...legacy, recurrenceCount: 2 }).length, 2);
});
check('non-first content edit preserves dates, IDs and every occurrence completion', () => {
    const completed = series.map((e, i) => ({ ...e, completed: i === 0, checklist: [{ ...e.checklist[0], completed: i === 1 }] }));
    const changed = buildRecurringEdit(completed, completed[1], { ...completed[1], title: '新标题', note: '新备注' });
    assert.deepEqual(dates(changed), dates(completed));
    assert.deepEqual(changed.map(e => e.id), completed.map(e => e.id));
    assert.deepEqual(changed.map(e => e.completed), [true, false, false]);
    assert.deepEqual(changed.map(e => e.checklist[0].completed), [false, true, false]);
    assert(changed.every(e => e.title === '新标题' && e.note === '新备注'));
});
check('checkbox updates are occurrence-local; template edits keep sibling progress', () => {
    assert.equal(expandSharedContentUpdates(series, [{ ...series[1], completed: true }]).length, 1);
    assert.equal(expandSharedContentUpdates(series, [{ ...series[1], checklist: [{ ...series[1].checklist[0], completed: true }] }]).length, 1);
    const modified = series.map((e, i) => ({ ...e, checklist: [{ ...e.checklist[0], completed: i === 0 }] }));
    const updates = expandSharedContentUpdates(modified, [{ ...modified[1], checklist: [{ ...modified[1].checklist[0], text: '新版第一章' }] }]);
    assert(updates.every(e => e.checklist[0].text === '新版第一章'));
    assert.equal(updates.find(e => e.id === modified[0].id).checklist[0].completed, true);
});
check('moving February occurrence changes whole series without losing January-31 anchor', () => {
    const selected = series[1];
    const moved = expandSharedContentUpdates(series, [{ ...selected, start: new Date(+selected.start + 3_600_000), end: new Date(+selected.end + 3_600_000) }]);
    assert.equal(moved.length, 3);
    for (const event of moved) {
        const previous = series.find(old => old.id === event.id);
        assert.equal(+new Date(event.start) - +previous.start, 3_600_000);
        assert.equal(event.recurrence.anchorStartAt, '2026-01-31T15:15:12.345Z');
    }
});
check('a deleted occurrence stays deleted on title edit or timeline movement', () => {
    const deleted = series.map((e, i) => i === 0 ? { ...e, deletedAt: 100 } : e);
    const updates = buildRecurringEdit(deleted, deleted[1], { ...deleted[1], title: '新标题' });
    assert.equal(updates.length, 2);
    assert(!updates.some(e => e.id === deleted[0].id));
});
check('shrink/expand retires IDs and never resurrects a tombstone, even after pruning', () => {
    const shrunk = buildRecurringEdit(series, series[1], { ...series[1], recurrenceCount: 2 }, 1234);
    assert.equal(shrunk.find(e => e.id === series[2].id).deletedAt, 1234);
    const retained = shrunk.filter(e => !e.deletedAt); // Simulate the 30-day local tombstone purge.
    const grown = buildRecurringEdit(retained, retained[1], { ...retained[1], recurrenceCount: 3 });
    assert.notEqual(grown.find(e => e.recurrence.occurrenceIndex === 2).id, series[2].id);
    assert.deepEqual(grown.map(e => e.id), buildRecurringEdit(grown, grown[1], grown[1]).map(e => e.id));
});
check('shrink retains completed history; cancelling keeps selected and completed members', () => {
    const completed = series.map((e, i) => ({ ...e, completed: i === 2 }));
    const shrunk = buildRecurringEdit(completed, completed[1], { ...completed[1], recurrenceCount: 2 }, 100);
    assert(!shrunk.find(e => e.id === completed[2].id).deletedAt);
    const cancelled = buildRecurringEdit(completed, completed[1], { ...completed[1], recurrenceType: 'none' }, 100);
    assert(cancelled.find(e => e.id === completed[0].id).deletedAt);
    assert(!cancelled.find(e => e.id === completed[1].id).deletedAt);
    assert(!cancelled.find(e => e.id === completed[2].id).deletedAt);
    assert(cancelled.every(e => e.recurrence === null));
});
check('pending projection advances/reopens chronologically and never mutates the timeline', () => {
    const completed = series.map((e, i) => ({ ...e, completed: i === 0 }));
    assert.deepEqual(selectNextPendingOccurrences(completed, { keepCompleted: true }).map(e => e.id), [series[1].id]);
    assert.deepEqual(selectNextPendingOccurrences(series).map(e => e.id), [series[0].id]);
    const allDone = series.map(e => ({ ...e, completed: true }));
    assert.deepEqual(selectNextPendingOccurrences(allDone, { keepCompleted: true }).map(e => e.id), [series[2].id]);
    assert.equal(selectNextPendingOccurrences(allDone).length, 0);
    assert.equal(series.length, 3);
    assert.equal(selectNextPendingOccurrences([series[1]])[0].id, series[1].id); // Today filter runs first.
});
check('gap moves forward and overlap chooses earlier offset', () => {
    assert.equal(occurrenceAt('2026-03-07T07:30:00Z', 'daily', 1, 'America/New_York').toISOString(), '2026-03-08T07:30:00.000Z');
    assert.equal(occurrenceAt('2026-10-31T05:30:00Z', 'daily', 1, 'America/New_York').toISOString(), '2026-11-01T05:30:00.000Z');
});
check('editor preserves exact original instants on content-only edit in another time zone', () => {
    const original = series[1];
    const result = scheduleFromEditor({ start: new Date(2026, 1, 28, 9, 15), end: new Date(2026, 1, 28, 10, 15), timeZone: 'America/New_York', original, dirty: false });
    assert.equal(+result.start, +original.start);
    assert.equal(+result.end, +original.end);
    const gap = scheduleFromEditor({ start: new Date(2026, 2, 8, 2, 30), end: new Date(2026, 2, 8, 4, 30), timeZone: 'America/New_York' });
    assert.equal(gap.start.toISOString(), '2026-03-08T07:30:00.000Z');
});
check('legacy Android ID recovery uses exact Java MD5 bytes, never title similarity', () => {
    const digest = createHash('md5').update(`${base.id}:recurrence:1`).digest();
    digest[6] = (digest[6] & 0x0f) | 0x30; digest[8] = (digest[8] & 0x3f) | 0x80;
    const hex = digest.toString('hex');
    const expected = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    assert.equal(legacyAndroidOccurrenceId(base.id, 1), expected);
    const independent = { ...base, id: 'independent', start: series[2].start };
    const recovered = recoverLegacySeries([base, { ...base, id: expected, start: series[1].start }, independent]);
    assert.equal(recovered[1].recurrence.seriesId, base.id);
    assert.equal(recovered[1].recurrence.occurrenceIndex, 1);
    assert(!recovered[2].recurrence?.seriesId);
});
check('explicit legacy groupId becomes recurrence metadata and detaching clears every alias', () => {
    const recovered = recoverLegacySeries([{ ...base, groupId: 'legacy-series' }, { ...base, id: 'second', start: series[1].start, extras: { groupId: 'legacy-series' } }]);
    assert(recovered.every(e => e.recurrence.seriesId === 'legacy-series' && !e.groupId && !e.extras?.groupId));
    const copy = detachFromSeries({ ...recovered[0], extras: { ...recovered[0].extras, _syncV3Recurrence: recovered[0].recurrence } });
    assert.equal(copy.recurrence, null);
    assert.equal(copy.recurrenceType, 'none');
    assert(!copy.extras._syncV3Recurrence);
});
check('V3 and RxDB preserve unknown recurrence/extras fields and round-trip without new commands', () => {
    const events = series.map(e => ({ ...e, recurrence: { ...e.recurrence, futureRule: { preserve: true } } }));
    let state = emptyState();
    for (const [index, command] of diffEventsToCommands(state, events).entries()) {
        const result = applyCommand(state, command, index + 1);
        assert(['APPLIED', 'NOOP'].includes(result.receipt.status), JSON.stringify(result.receipt));
        state = result.state;
    }
    const projected = toUiEvents(state);
    assert(projected.every(e => e.recurrence.futureRule.preserve && e.extras.futureExtra === 'keep'));
    assert.equal(diffEventsToCommands(state, projected).length, 0);
    const stored = toDatabaseEvent({ ...projected[0], futureTopLevel: 'keep' });
    assert.equal(eventSchema.version, 4);
    assert.equal(stored.recurrence.futureRule.preserve, true);
    assert.equal(stored.extras.futureTopLevel, 'keep');
    assert.equal(typeof stored.start, 'string');
    const edited = { ...projected[0], recurrenceCount: 4 };
    const recurrenceCommand = diffEventsToCommands(state, [edited]).find(command => command.type === 'task.setRecurrence');
    assert.equal(recurrenceCommand.arguments.recurrence.count, 4);
    assert.equal(recurrenceCommand.arguments.recurrence.futureRule.preserve, true);
});
check('stale editor applies only user delta and preserves remote content, progress and rule extensions', () => {
    const original = series[1];
    const latest = series.map(event => ({ ...event, note: '远程备注', colorId: 6,
        recurrence: { ...event.recurrence, remoteExtension: 'keep' },
        checklist: [{ ...event.checklist[0], completed: true }, { id: 'remote-child', text: '远程新增', completed: false }],
        completed: event.id === original.id,
    }));
    const updates = buildRecurringEdit(latest, original, { ...original, title: '用户新标题' });
    assert(updates.every(event => event.title === '用户新标题' && event.note === '远程备注' && event.colorId === 6));
    assert(updates.every(event => event.recurrence.remoteExtension === 'keep' && event.checklist.length === 2));
    assert(updates.find(event => event.id === original.id).completed);
    const checklistEdit = buildRecurringEdit(latest, original, { ...original, checklist: [{ ...original.checklist[0], text: '用户改子项' }] });
    assert(checklistEdit.every(event => event.checklist[0].text === '用户改子项' && event.checklist[0].completed));
    assert(checklistEdit.every(event => event.checklist.some(item => item.id === 'remote-child')));
    const remoteMoved = latest.map(event => ({ ...event, start: new Date(+event.start + 3600000), end: new Date(+event.end + 3600000),
        recurrence: { ...event.recurrence, anchorStartAt: '2026-01-31T15:15:12.345Z', anchorEndAt: '2026-01-31T16:15:12.345Z' } }));
    assert.deepEqual(dates(buildRecurringEdit(remoteMoved, original, { ...original, title: '标题而已' })), dates(remoteMoved));
});
check('deleted or missing source is rejected rather than restored from an old editor', () => {
    const original = detachFromSeries(base);
    assert.throws(() => buildRecurringEdit([{ ...original, deletedAt: 100 }], original, { ...original, title: '旧编辑' }), /已被删除/);
    assert.throws(() => buildRecurringEdit([], original, original), /已不存在/);
});
check('unknown recurrence survives ordinary rename and can be explicitly cancelled', () => {
    const future = series.map(event => ({ ...event, recurrenceType: 'yearly', recurrence: { ...event.recurrence, frequency: 'yearly', futureRule: { days: [1, 8] } } }));
    const renamed = buildRecurringEdit(future, future[1], { ...future[1], title: '未来规则改名' });
    assert(renamed.every(event => event.title === '未来规则改名'));
    assert.deepEqual(renamed.map(event => event.recurrence), future.map(event => event.recurrence));
    const cancelled = buildRecurringEdit(future, future[1], { ...future[1], recurrenceType: 'none' }, 100, { recurrenceChanged: true });
    assert(cancelled.every(event => event.recurrence === null));
});
check('cancel/purge/reenable and single-delete/purge/resize never reuse occurrence IDs', () => {
    const cancelled = buildRecurringEdit(series, series[0], { ...series[0], recurrenceType: 'none' }, 100);
    const kept = cancelled.filter(event => !event.deletedAt);
    const rebuilt = buildRecurringEdit(kept, kept[0], { ...kept[0], recurrenceType: 'monthly', recurrenceCount: 3 });
    assert(rebuilt.slice(1).every(event => !series.some(old => old.id === event.id)));
    const removed = deleteRecurringOccurrences(series, [series[2].id], 100);
    const live = removed.filter(event => !event.deletedAt);
    assert(live.every(event => event.extras._retiredRecurringOccurrenceIds.includes(series[2].id)));
    const shrunk = buildRecurringEdit(live, live[0], { ...live[0], recurrenceCount: 2 });
    const grown = buildRecurringEdit(shrunk, shrunk[0], { ...shrunk[0], recurrenceCount: 3 });
    assert.notEqual(grown.find(event => event.recurrence.occurrenceIndex === 2).id, series[2].id);
});
check('editing completed historical date shifts future by only the actual user delta', () => {
    const completed = series.map((event, index) => ({ ...event, completed: index === 0 }));
    const moved = buildRecurringEdit(completed, completed[1], { ...completed[1], start: new Date(+completed[1].start + 86400000), end: new Date(+completed[1].end + 86400000) });
    assert.equal(+moved[0].start, +completed[0].start);
    const historyDraft = { ...moved[0], start: new Date(+moved[0].start + 3600000), end: new Date(+moved[0].end + 3600000) };
    const edited = buildRecurringEdit(moved, moved[0], historyDraft);
    assert.equal(+edited[0].start, +historyDraft.start);
    assert.equal(+edited[1].start - +moved[1].start, 3600000);
    assert.equal(+edited[2].start - +moved[2].start, 3600000);
});
console.log(`${checks} recurrence checks passed.`);
