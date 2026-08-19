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
});
