// Overlap-group lane assignment for the horizontal timeline.
//
// The horizontal timeline maps Google Calendar's vertical algorithm 90°:
// time runs left→right (EventBlock's left/width), while the conflict layout
// runs top→bottom (laneTopPct/laneHeightPct). Lanes must be LOCAL to an
// overlap group, not day-global: an isolated afternoon event must not be
// squeezed to 1/3 height because the morning had three concurrent events.
import { areIntervalsOverlapping } from 'date-fns';

/**
 * Split lane-eligible events into maximal overlap groups and greedily assign
 * lanes inside each group.
 *
 * Events are sorted by start. An event opens a new group when its start no
 * longer overlaps the group's running max end; touching endpoints count as
 * no overlap, matching the lane conflict rule's `inclusive: false` semantics.
 * Inside a group each event goes into the first lane with no conflict.
 *
 * @param {Array<{start: Date, end: Date}>} events lane-eligible events
 * @returns {{assigned: Array, groups: Array}}
 *   assigned[i]  is the i-th event (sorted by start) plus
 *                { groupIndex, laneIdx, groupLaneCount }
 *   groups[g]    is { laneCount, start, end } for the g-th group in time order
 */
export function assignOverlapGroupLanes(events) {
    const sorted = [...events].sort((a, b) => a.start - b.start);

    const assigned = [];
    const groups = [];

    let groupIndex = 0;
    let lanes = [];
    let groupMaxEndMs = null;
    let groupStart = null;
    let members = [];

    const flush = () => {
        if (members.length === 0) return;
        const laneCount = lanes.length || 1;
        groups.push({ laneCount, start: groupStart, end: new Date(groupMaxEndMs) });
        for (const member of members) {
            assigned.push({
                ...member.event,
                groupIndex,
                laneIdx: member.laneIdx,
                groupLaneCount: laneCount,
            });
        }
        groupIndex += 1;
        lanes = [];
        groupMaxEndMs = null;
        groupStart = null;
        members = [];
    };

    for (const ev of sorted) {
        if (groupMaxEndMs !== null && ev.start.getTime() >= groupMaxEndMs) flush();

        let laneIdx = 0;
        while (true) {
            const lane = lanes[laneIdx];
            if (!lane) { lanes[laneIdx] = [ev]; break; }
            const hasConflict = lane.some(existing =>
                areIntervalsOverlapping(
                    { start: existing.start, end: existing.end },
                    { start: ev.start, end: ev.end },
                    { inclusive: false },
                )
            );
            if (!hasConflict) { lane.push(ev); break; }
            laneIdx += 1;
        }

        if (groupStart === null) groupStart = ev.start;
        groupMaxEndMs = Math.max(groupMaxEndMs ?? 0, ev.end.getTime());
        members.push({ event: ev, laneIdx });
    }
    flush();

    return { assigned, groups };
}

/**
 * Turn per-group columns into cascade geometry (TPlanner's rotated Google
 * algorithm). The conflict axis is vertical:
 *
 *   column 0 → top = eventGap
 *   column i → top = eventGap + i × eventSummaryHeight
 *   height   = eventAreaHeight − 2×eventGap − i × eventSummaryHeight
 *
 * Every column bottom-aligns to the same edge, so a later column covers the
 * BODY of earlier columns while each event's fixed eventSummaryHeight
 * recognition zone stays visible. The event area grows with the day's
 * maximum column count so the deepest column keeps at least one full summary
 * height of body. The algorithm knows nothing about note or content — only
 * the summary height.
 *
 * @param {{assigned: Array, groups: Array}} layout result of assignOverlapGroupLanes
 * @param {{eventGap: number, eventSummaryHeight: number, eventMinHeight: number,
 *          eventAreaBaseHeight: number}} tokens timeline tokens
 * @returns {{rows: Array, eventAreaHeight: number, maxColumns: number}}
 *   rows[i] is { event, topPx, heightPx } for the i-th assigned event;
 *   topPx is relative to the top of the event area (add the status strip).
 */
export function computeCascadeLayout({ assigned, groups, tokens }) {
    const maxColumns = groups.reduce((maxLanes, g) => Math.max(maxLanes, g.laneCount), 1);
    const eventAreaHeight = Math.max(
        tokens.eventAreaBaseHeight,
        maxColumns * tokens.eventMinHeight,
    );
    const rows = assigned.map(ev => ({
        event: ev,
        topPx: tokens.eventGap + ev.laneIdx * tokens.eventSummaryHeight,
        heightPx: eventAreaHeight - 2 * tokens.eventGap - ev.laneIdx * tokens.eventSummaryHeight,
    }));
    return { rows, eventAreaHeight, maxColumns };
}
