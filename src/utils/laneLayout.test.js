import { describe, it, expect } from 'vitest';
import { assignOverlapGroupLanes } from './laneLayout';

const t = (startH, startM, endH, endM) => ({
    start: new Date(2026, 8, 1, startH, startM),
    end: new Date(2026, 8, 1, endH, endM),
});

function byId(assigned, pick) {
    return assigned.map(ev => pick(ev));
}

describe('assignOverlapGroupLanes', () => {
    it('keeps an isolated afternoon event at full height after a 3-way morning clash', () => {
        const events = [t(9, 0, 12, 0), t(9, 0, 12, 0), t(9, 0, 12, 0), t(14, 0, 17, 0)];
        const { assigned, groups } = assignOverlapGroupLanes(events);

        expect(groups.map(g => g.laneCount)).toEqual([3, 1]);
        // Afternoon event is its own group with a single full-height lane.
        expect(byId(assigned, ev => [ev.groupIndex, ev.laneIdx, ev.groupLaneCount]))
            .toEqual([[0, 0, 3], [0, 1, 3], [0, 2, 3], [1, 0, 1]]);
    });

    it('treats a chain of overlaps as one group and reuses freed lanes', () => {
        const events = [t(9, 0, 11, 0), t(10, 0, 12, 0), t(11, 0, 13, 0)];
        const { assigned, groups } = assignOverlapGroupLanes(events);

        expect(groups).toHaveLength(1);
        expect(groups[0].laneCount).toBe(2);
        expect(byId(assigned, ev => ev.laneIdx)).toEqual([0, 1, 0]);
    });

    it('splits touching intervals into separate groups (inclusive: false)', () => {
        const events = [t(9, 0, 10, 0), t(10, 0, 11, 0)];
        const { groups } = assignOverlapGroupLanes(events);
        expect(groups.map(g => g.laneCount)).toEqual([1, 1]);
    });

    it('puts identical intervals in separate lanes of one group', () => {
        const events = [t(9, 0, 10, 0), t(9, 0, 10, 0), t(9, 0, 10, 0)];
        const { assigned, groups } = assignOverlapGroupLanes(events);
        expect(groups.map(g => g.laneCount)).toEqual([3]);
        expect(byId(assigned, ev => ev.laneIdx)).toEqual([0, 1, 2]);
    });

    it('handles two independent overlap clusters in one day', () => {
        const events = [t(9, 0, 10, 0), t(9, 30, 10, 30), t(14, 0, 15, 0), t(14, 30, 15, 30)];
        const { assigned, groups } = assignOverlapGroupLanes(events);
        expect(groups.map(g => g.laneCount)).toEqual([2, 2]);
        expect(byId(assigned, ev => [ev.groupIndex, ev.laneIdx]))
            .toEqual([[0, 0], [0, 1], [1, 0], [1, 1]]);
    });

    it('needs three lanes only while three events actually overlap', () => {
        const events = [t(9, 0, 11, 0), t(10, 0, 14, 0), t(10, 0, 11, 0), t(13, 0, 14, 0)];
        const { assigned, groups } = assignOverlapGroupLanes(events);
        expect(groups.map(g => g.laneCount)).toEqual([3]);
        expect(byId(assigned, ev => ev.laneIdx)).toEqual([0, 1, 2, 0]);
    });

    it('records each group time span for layout follow-ups', () => {
        const events = [t(9, 0, 12, 0), t(9, 0, 12, 0), t(15, 0, 16, 0)];
        const { groups } = assignOverlapGroupLanes(events);
        expect(groups).toHaveLength(2);
        expect(groups[0].start).toEqual(t(9, 0, 12, 0).start);
        expect(groups[0].end).toEqual(t(9, 0, 12, 0).end);
        expect(groups[1].start).toEqual(t(15, 0, 16, 0).start);
    });

    it('returns empty results for no events', () => {
        expect(assignOverlapGroupLanes([])).toEqual({ assigned: [], groups: [] });
    });

    it('does not mutate the input array or events', () => {
        const events = [t(9, 0, 10, 0), t(14, 0, 15, 0)];
        const snapshot = events.map(ev => ({ ...ev, start: new Date(ev.start), end: new Date(ev.end) }));
        assignOverlapGroupLanes(events);
        expect(events).toEqual(snapshot);
    });
});
