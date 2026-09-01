import { describe, test, expect } from 'vitest';
import { applyCommand, emptyState } from './localReducer';
import sequence01Input from '../../sync-v3/protocol/v3/fixtures/reducer/sequence-01.input.json';
import sequence01State from '../../sync-v3/protocol/v3/fixtures/reducer/sequence-01.expected-state.json';
import sequence01Receipts from '../../sync-v3/protocol/v3/fixtures/reducer/sequence-01.expected-receipts.json';

// 契约测试:与服务器 reducer 共用同一份 fixture,产出必须逐键一致
// —— 这是"任何端安装同一版本后 canonical state 完全一致"的跨实现证明。
describe('local reducer determinism', () => {
    test('replays sequence-01 to the exact expected state and receipts', () => {
        let state = emptyState();
        const receipts = [];
        for (const entry of sequence01Input.commands) {
            const result = applyCommand(state, entry.command, entry.brokerSequence);
            state = result.state;
            receipts.push({ brokerSequence: entry.brokerSequence, status: result.receipt.status });
        }
        expect(state).toEqual(sequence01State);
        expect({ receipts }).toEqual(sequence01Receipts);
    });

    test('pending overlay over a server mirror keeps the local edit visible', () => {
        // Server Mirror 尚未包含本地新建任务;本地 pending 归约到其上
        let state = emptyState();
        ({ state } = applyCommand(state, {
            type: 'task.create',
            aggregateId: 't-local',
            arguments: { title: '离线创建' },
        }, 1));
        expect(state.tasks['t-local'].title).toBe('离线创建');
        expect(state.tasks['t-local'].lifecycle).toBe('active');
    });

    test('forward-compatible alarm and location fields survive optimistic reduction', () => {
        let state = emptyState();
        ({ state } = applyCommand(state, {
            type: 'task.create', aggregateId: 't1', arguments: { title: 'x' },
        }, 1));
        ({ state } = applyCommand(state, {
            type: 'task.setAlarm',
            aggregateId: 't1',
            arguments: { enabled: true, offsetMinutes: 15, futureSound: { name: 'chime' } },
        }, 2));
        ({ state } = applyCommand(state, {
            type: 'task.setLocation',
            aggregateId: 't1',
            arguments: { lat: 31.23, lng: 121.47, futureAccuracy: 3 },
        }, 3));
        ({ state } = applyCommand(state, {
            type: 'task.setAlarm',
            aggregateId: 't1',
            arguments: { enabled: false, offsetMinutes: 5 },
        }, 4));
        ({ state } = applyCommand(state, {
            type: 'task.setLocation',
            aggregateId: 't1',
            arguments: { lat: null, lng: null },
        }, 5));

        expect(state.tasks.t1.alarm.futureSound).toEqual({ name: 'chime' });
        expect(state.tasks.t1.location.futureAccuracy).toBe(3);
    });

    test('bootstrap guards never overwrite existing central values', () => {
        let state = emptyState();
        let sequence = 0;
        const apply = (type, aggregateId, args = {}) => {
            const result = applyCommand(state, { type, aggregateId, arguments: args }, ++sequence);
            state = result.state;
            return result.receipt;
        };

        apply('task.create', 't1', { title: 'central' });
        apply('list.create', 'central-list', { title: 'central' });
        apply('list.create', 'local-list', { title: 'local' });
        apply('task.setSchedule', 't1', {
            schedule: { startAt: '2026-09-01T01:00:00.000Z', endAt: null },
        });
        apply('task.setRecurrence', 't1', {
            recurrence: { frequency: 'weekly', count: 2, futureRule: 'central' },
        });
        apply('task.setAlarm', 't1', {
            enabled: true, offsetMinutes: 30, futureSound: 'central',
        });
        apply('task.setLocation', 't1', {
            lat: 31.23, lng: 121.47, futureAccuracy: 5,
        });
        apply('task.setExtras', 't1', {
            extras: { shared: 'central', remoteOnly: true },
        });
        apply('task.assignList', 't1', { listId: 'central-list' });
        apply('journal.setText', '2026-09-01', { text: 'central journal' });

        expect(apply('task.setSchedule', 't1', {
            schedule: { startAt: '2026-10-01T01:00:00.000Z', endAt: null },
            ifMissing: true,
        }).status).toBe('NOOP');
        expect(apply('task.setRecurrence', 't1', {
            recurrence: { frequency: 'daily', count: 1 },
            ifMissing: true,
        }).status).toBe('NOOP');
        expect(apply('task.setAlarm', 't1', {
            enabled: false, offsetMinutes: 0, ifMissing: true,
        }).status).toBe('NOOP');
        expect(apply('task.setLocation', 't1', {
            lat: 0, lng: 0, ifMissing: true,
        }).status).toBe('NOOP');
        expect(apply('task.assignList', 't1', {
            listId: 'local-list', ifUnassigned: true,
        }).status).toBe('NOOP');
        expect(apply('journal.setText', '2026-09-01', {
            text: 'local journal', ifMissing: true,
        }).status).toBe('NOOP');
        expect(apply('task.setExtras', 't1', {
            extras: { shared: 'local', localOnly: true }, mergeMissing: true,
        }).status).toBe('APPLIED');

        expect(state.tasks.t1.schedule).toEqual({
            startAt: '2026-09-01T01:00:00.000Z', endAt: null,
        });
        expect(state.tasks.t1.recurrence).toEqual({
            frequency: 'weekly', count: 2, futureRule: 'central',
        });
        expect(state.tasks.t1.alarm).toEqual({
            enabled: true, offsetMinutes: 30, futureSound: 'central',
        });
        expect(state.tasks.t1.location).toEqual({
            lat: 31.23, lng: 121.47, futureAccuracy: 5,
        });
        expect(state.tasks.t1.extras).toEqual({
            localOnly: true, shared: 'central', remoteOnly: true,
        });
        expect(state.tasks.t1.listId).toBe('central-list');
        expect(state.journals['2026-09-01'].text).toBe('central journal');
    });

    test('bootstrap guards fill missing values without entering canonical state', () => {
        let state = emptyState();
        let sequence = 0;
        const apply = (type, aggregateId, args = {}) => {
            ({ state } = applyCommand(state, { type, aggregateId, arguments: args }, ++sequence));
        };

        apply('task.create', 't1', { title: 'local-only fields' });
        apply('list.create', 'local-list', { title: 'local' });
        apply('task.setSchedule', 't1', {
            schedule: { startAt: '2026-09-02T01:00:00.000Z', endAt: null },
            ifMissing: true,
        });
        apply('task.setRecurrence', 't1', {
            recurrence: { frequency: 'daily', count: 1 }, ifMissing: true,
        });
        apply('task.setAlarm', 't1', {
            enabled: true, offsetMinutes: 10, futureSound: 'local', ifMissing: true,
        });
        apply('task.setLocation', 't1', {
            lat: 30.1, lng: 120.2, futureAccuracy: 7, ifMissing: true,
        });
        apply('task.setExtras', 't1', {
            extras: { localOnly: true }, mergeMissing: true,
        });
        apply('task.assignList', 't1', { listId: 'local-list', ifUnassigned: true });
        apply('journal.setText', '2026-09-02', { text: 'local journal', ifMissing: true });

        expect(state.tasks.t1.schedule).toEqual({
            startAt: '2026-09-02T01:00:00.000Z', endAt: null,
        });
        expect(state.tasks.t1.recurrence).toEqual({ frequency: 'daily', count: 1 });
        expect(state.tasks.t1.alarm).toEqual({
            enabled: true, offsetMinutes: 10, futureSound: 'local',
        });
        expect(state.tasks.t1.location).toEqual({
            lat: 30.1, lng: 120.2, futureAccuracy: 7,
        });
        expect(state.tasks.t1.extras).toEqual({ localOnly: true });
        expect(state.tasks.t1.listId).toBe('local-list');
        expect(state.journals['2026-09-02']).toEqual({
            text: 'local journal', lifecycle: 'active', deletedAt: null,
        });
        expect(JSON.stringify(state)).not.toMatch(/ifMissing|mergeMissing|ifUnassigned/);
    });
});
