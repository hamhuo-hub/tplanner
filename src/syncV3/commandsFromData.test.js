import { describe, test, expect } from 'vitest';
import { emptyState } from './localReducer';
import { diffEventsToCommands, diffJournalsToCommands, toLegacyEvents, toLegacyJournals } from './commandsFromData';

const mirrorWithTask = (id, patch) => ({
    ...emptyState(),
    tasks: { [id]: { title: '旧标题', note: '', completed: false, itemType: 'task', lifecycle: 'active', deletedAt: null, ...patch } },
});

describe('commandsFromData', () => {
    test('new legacy event diffs into create + field commands with schedule mapping', () => {
        const mirror = emptyState();
        const commands = diffEventsToCommands(mirror, [
            { id: 'e1', title: '开会', note: 'n', completed: true, start: new Date('2026-08-20T01:00:00Z'), end: null },
        ]);
        expect(commands.map((c) => c.type)).toEqual(['task.create', 'task.setNote', 'task.setCompleted', 'task.setSchedule']);
        expect(commands[3].arguments.schedule.startAt).toBe('2026-08-20T01:00:00.000Z');
    });

    test('single field change yields a single command; unchanged yields none', () => {
        const mirror = mirrorWithTask('e1');
        expect(diffEventsToCommands(mirror, [
            { id: 'e1', title: '新', note: '', completed: false, start: null },
        ]).map((c) => c.type)).toEqual(['task.setTitle']);

        expect(diffEventsToCommands(mirror, [
            { id: 'e1', title: '旧标题', note: '', completed: false, start: null },
        ])).toHaveLength(0);
    });

    test('delete and restore map to lifecycle commands', () => {
        const mirror = mirrorWithTask('e1');
        expect(diffEventsToCommands(mirror, [
            { id: 'e1', title: '旧标题', note: '', deletedAt: new Date() },
        ]).map((c) => c.type)).toEqual(['task.delete']);

        const deletedMirror = mirrorWithTask('e1', { lifecycle: 'deleted', deletedAt: 7 });
        expect(diffEventsToCommands(deletedMirror, [
            { id: 'e1', title: '旧标题', note: '', completed: false, start: null },
        ]).map((c) => c.type)).toEqual(['task.restore']);
    });

    test('journal diffs: create, edit, delete; deleted journal is not revived', () => {
        const mirror = { ...emptyState(), journals: { '2026-08-19': { text: '旧', lifecycle: 'active', deletedAt: null } } };

        expect(diffJournalsToCommands(mirror, { '2026-08-20': { text: '新日记' } }).map((c) => c.type)).toEqual(['journal.setText']);
        expect(diffJournalsToCommands(mirror, { '2026-08-19': { text: '改' } }).map((c) => c.type)).toEqual(['journal.setText']);
        expect(diffJournalsToCommands(mirror, { '2026-08-19': { text: 'x', deletedAt: 1 } }).map((c) => c.type)).toEqual(['journal.delete']);
        expect(diffJournalsToCommands(mirror, { '2026-08-19': { text: '旧' } })).toHaveLength(0);

        const deletedMirror = { ...emptyState(), journals: { '2026-08-19': { text: '旧', lifecycle: 'deleted', deletedAt: 1 } } };
        expect(diffJournalsToCommands(deletedMirror, { '2026-08-19': { text: '复活' } })).toHaveLength(0);
    });

    test('projections round-trip to legacy UI shapes', () => {
        const state = {
            ...emptyState(),
            tasks: {
                'e1': { title: '开会', note: '', completed: true, itemType: 'task', schedule: { startAt: '2026-08-20T01:00:00.000Z', endAt: null }, lifecycle: 'active', deletedAt: null },
                'e2': { title: '已删', note: '', completed: false, itemType: 'task', lifecycle: 'deleted', deletedAt: 9 },
            },
            journals: { '2026-08-19': { text: '日记', lifecycle: 'active', deletedAt: null } },
        };

        const events = toLegacyEvents(state);
        expect(events).toHaveLength(2);
        const e1 = events.find((e) => e.id === 'e1');
        expect(e1.start.toISOString()).toBe('2026-08-20T01:00:00.000Z');
        expect(e1.completed).toBe(true);
        const e2 = events.find((e) => e.id === 'e2');
        expect(e2.deletedAt).toBeInstanceOf(Date);

        expect(toLegacyJournals(state)['2026-08-19'].text).toBe('日记');
    });
});
