import { describe, test, expect } from 'vitest';
import { applyCommand, emptyState } from './localReducer';
import { diffEventsToCommands, diffJournalsToCommands, toUiEvents, toUiJournals } from './commandsFromData';

const mirrorWithTask = (id, patch) => ({
    ...emptyState(),
    tasks: { [id]: { title: '旧标题', note: '', completed: false, itemType: 'task', lifecycle: 'active', deletedAt: null, ...patch } },
});

describe('commandsFromData', () => {
    test('new UI event diffs into create + field commands with schedule mapping', () => {
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

    test('projections round-trip to UI shapes', () => {
        const state = {
            ...emptyState(),
            tasks: {
                'e1': { title: '开会', note: '', completed: true, itemType: 'task', schedule: { startAt: '2026-08-20T01:00:00.000Z', endAt: null }, lifecycle: 'active', deletedAt: null },
                'e2': { title: '已删', note: '', completed: false, itemType: 'task', lifecycle: 'deleted', deletedAt: 9 },
            },
            journals: { '2026-08-19': { text: '日记', lifecycle: 'active', deletedAt: null } },
        };

        const events = toUiEvents(state);
        expect(events).toHaveLength(2);
        const e1 = events.find((e) => e.id === 'e1');
        expect(e1.start.toISOString()).toBe('2026-08-20T01:00:00.000Z');
        expect(e1.completed).toBe(true);
        const e2 = events.find((e) => e.id === 'e2');
        expect(e2.deletedAt).toBeInstanceOf(Date);

        expect(toUiJournals(state)['2026-08-19'].text).toBe('日记');
    });

    test('round-trips every persistent task field through semantic commands', () => {
        let state = {
            ...emptyState(),
            customLists: {
                work: { title: '工作', color: null, lifecycle: 'active', deletedAt: null },
            },
        };
        const source = {
            id: 'full-task',
            title: '完整任务',
            type: 'task',
            note: '正文',
            completed: false,
            start: new Date('2026-09-01T01:00:00.000Z'),
            end: new Date('2026-09-01T02:00:00.000Z'),
            checklist: [
                { id: 'c1', text: '第一项', completed: true },
                { id: 'c2', text: '第二项', completed: false },
            ],
            listId: 'work',
            recurrenceType: 'weekly',
            recurrenceCount: 10,
            colorId: 4,
            alarmEnabled: true,
            alarmOffsetMinutes: -15,
            lat: 31.2304,
            lng: 121.4737,
            extras: { futureField: { nested: true } },
        };

        const commands = diffEventsToCommands(state, [source]);
        for (const [index, command] of commands.entries()) {
            state = applyCommand(state, command, index + 1).state;
        }

        const restored = toUiEvents(state).find((event) => event.id === source.id);
        expect(restored).toMatchObject({
            title: source.title,
            note: source.note,
            type: 'task',
            listId: 'work',
            recurrenceType: 'weekly',
            recurrenceCount: 10,
            colorId: 4,
            alarmEnabled: true,
            alarmOffsetMinutes: -15,
            lat: source.lat,
            lng: source.lng,
            extras: source.extras,
            checklist: source.checklist,
        });
    });
});
