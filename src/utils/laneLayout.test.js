import { describe, it, expect } from 'vitest';
import { assignOverlapGroupLanes, computeCascadeLayout } from './laneLayout';
import { timeline } from '../design-system/tokens';

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

describe('computeCascadeLayout', () => {
    const geom = (events) => {
        const { assigned, groups } = assignOverlapGroupLanes(events);
        return computeCascadeLayout({ assigned, groups, tokens: timeline });
    };

    it('stagger columns by exactly eventSummaryHeight and bottom-align all of them', () => {
        const events = [t(9, 0, 12, 0), t(9, 0, 12, 0), t(9, 0, 12, 0)];
        const { rows, eventAreaHeight, maxColumns } = geom(events);

        expect(maxColumns).toBe(3);
        expect(eventAreaHeight).toBe(3 * timeline.eventMinHeight);
        expect(rows.map(r => r.topPx)).toEqual([
            timeline.eventGap,
            timeline.eventGap + timeline.eventSummaryHeight,
            timeline.eventGap + 2 * timeline.eventSummaryHeight,
        ]);
        expect(rows.map(r => r.heightPx)).toEqual([
            eventAreaHeight - 2 * timeline.eventGap,
            eventAreaHeight - 2 * timeline.eventGap - timeline.eventSummaryHeight,
            eventAreaHeight - 2 * timeline.eventGap - 2 * timeline.eventSummaryHeight,
        ]);
        // All columns share the same bottom edge — height = availableHeight - top.
        const bottoms = new Set(rows.map(r => r.topPx + r.heightPx));
        expect(bottoms.size).toBe(1);
    });

    it('is not equal division: a later column keeps the remaining height', () => {
        const events = [t(9, 0, 12, 0), t(9, 0, 12, 0), t(9, 0, 12, 0)];
        const { rows } = geom(events);
        // Column 0 gets the full area, columns 1/2 cascade — not 1/3 each.
        expect(rows[0].heightPx).toBeGreaterThan(rows[1].heightPx);
        expect(rows[1].heightPx).toBeGreaterThan(rows[2].heightPx);
    });

    it('gives an isolated afternoon event the full area height', () => {
        const events = [t(9, 0, 12, 0), t(9, 0, 12, 0), t(14, 0, 17, 0)];
        const { rows, eventAreaHeight } = geom(events);
        // Afternoon event is column 0 of its group → full height, even though
        // the day's max column count is 3.
        expect(rows[2].topPx).toBe(timeline.eventGap);
        expect(rows[2].heightPx).toBe(eventAreaHeight - 2 * timeline.eventGap);
    });

    it('grows the event area only with the day max column count', () => {
        expect(geom([t(9, 0, 10, 0)]).eventAreaHeight).toBe(timeline.eventAreaBaseHeight);
        expect(geom([t(9, 0, 10, 0), t(9, 30, 10, 30)]).eventAreaHeight).toBe(2 * timeline.eventMinHeight);
    });

    it('keeps at least a full summary of body for the deepest column', () => {
        const { rows } = geom([t(9, 0, 12, 0), t(9, 0, 12, 0), t(9, 0, 12, 0)]);
        const deepest = rows[2];
        expect(deepest.heightPx).toBeGreaterThanOrEqual(timeline.eventSummaryHeight);
    });

    it('overlapReveal equals the full summary height token', () => {
        const { rows } = geom([t(9, 0, 12, 0), t(9, 0, 12, 0), t(9, 0, 12, 0)]);
        expect(rows[1].topPx - rows[0].topPx).toBe(timeline.eventSummaryHeight);
        expect(rows[2].topPx - rows[1].topPx).toBe(timeline.eventSummaryHeight);
    });
});
