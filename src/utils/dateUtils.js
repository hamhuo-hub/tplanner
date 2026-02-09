import { startOfDay, addDays, isBefore, isAfter, areIntervalsOverlapping, parseISO } from 'date-fns';

/**
 * Calculates the start and end dates for the timeline view.
 * Logic: Start from the earliest event date (or today if no events/events are in past) 
 *        and show 14 days. 
 *        Actually, plan says "earliest active task". 
 *        Let's interpret "active" as "future or today". 
 *        If all events are past, maybe default to today?
 * @param {import('./constants').Event[]} events 
 * @returns {{startDate: Date, endDate: Date}}
 */
export const calculateTimelineRange = (events, referenceDate = null) => {
    let startDate;

    if (referenceDate) {
        startDate = startOfDay(referenceDate);
    } else {
        const today = startOfDay(new Date());
        let earliestDate = today;

        if (events.length > 0) {
            const sortedEvents = [...events].sort((a, b) => a.start - b.start);
            earliestDate = startOfDay(sortedEvents[0].start);
        }
        startDate = earliestDate;
    }

    // 2 months view (approx 60 days)
    const endDate = addDays(startDate, 60);

    return { startDate, endDate };
};

/**
 * Detects value clashes between events.
 * @param {import('./constants').Event[]} events 
 * @returns {import('./constants').Clash[]}
 */
export const checkForClashes = (events) => {
    const clashes = [];

    for (let i = 0; i < events.length; i++) {
        for (let j = i + 1; j < events.length; j++) {
            const eventA = events[i];
            const eventB = events[j];

            // Check for overlap
            if (areIntervalsOverlapping(
                { start: eventA.start, end: eventA.end },
                { start: eventB.start, end: eventB.end }
            )) {
                // Calculate overlap duration in minutes
                // Overlap start is max(startA, startB)
                // Overlap end is min(endA, endB)
                const overlapStart = new Date(Math.max(eventA.start, eventB.start));
                const overlapEnd = new Date(Math.min(eventA.end, eventB.end));
                const duration = (overlapEnd - overlapStart) / (1000 * 60);

                clashes.push({
                    eventId: eventA.id,
                    clashWithId: eventB.id,
                    overlapMinutes: duration,
                    start: overlapStart,
                    end: overlapEnd
                });

                // Add reciprocal clash for easier lookup? 
                // Usually better to have unique pairs or just list ids involved.
                // Let's add double entries so we can easily find "clashes for event X".
                clashes.push({
                    eventId: eventB.id,
                    clashWithId: eventA.id,
                    overlapMinutes: duration
                });
            }
        }
    }

    return clashes;
};
