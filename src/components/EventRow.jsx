import { format, areIntervalsOverlapping, startOfDay, endOfDay, max, min, addMinutes } from 'date-fns';
import EventBlock from './EventBlock';

/**
 * @param {Object} props
 * @param {Date} props.date
 * @param {import('../utils/constants').Event[]} props.events
 * @param {Function} props.onEventClick
 * @param {Function} props.onAddEvent
 */
export default function EventRow({ date, events, onEventClick, onAddEvent, highlight }) {
    const dayStart = startOfDay(date);
    const dayEnd = endOfDay(date);

    // 1. Filter events overlapping with this day
    const dayEvents = events.filter(e =>
        areIntervalsOverlapping(
            { start: e.start, end: e.end },
            { start: dayStart, end: dayEnd }
        )
    ).map(e => {
        // 2. Clamp start/end to this day for rendering
        const clampedStart = max([e.start, dayStart]);
        const clampedEnd = min([e.end, dayEnd]);
        return {
            ...e,
            originalStart: e.start,
            originalEnd: e.end,
            start: clampedStart,
            end: clampedEnd
        };
    }).sort((a, b) => a.start - b.start);

    // 3. Simple layout algorithm for overlaps
    // Assign "tracks" to events. overlapping events get different tracks.
    // Group overlapping events
    const processedEvents = [];
    if (dayEvents.length > 0) {
        // Simple approach: check overlaps with already placed events in this row
        // If event A overlaps event B, they share height.
        // We will assign a 'group' index and 'totalGroups' for each event to style top/height.

        // This is a complex packing problem, but "average occupy" implies splitting height.
        // Let's sweep: for each event, find all other events that overlap it IN THIS DAY.
        // Determine the maximum number of concurrent events at any point in this event's duration.
        // Then assign a slot index.

        // Simpler greedy approach for "average occupy" (like Outlook):
        // 1. Calculate concurrency for every minute? Too expensive.
        // 2. Cluster events that overlap.

        const clusters = [];
        let currentCluster = [];
        let clusterEnd = null;

        dayEvents.forEach(event => {
            if (!clusterEnd || event.start < clusterEnd) {
                currentCluster.push(event);
                clusterEnd = clusterEnd ? max([clusterEnd, event.end]) : event.end;
            } else {
                clusters.push(currentCluster);
                currentCluster = [event];
                clusterEnd = event.end;
            }
        });
        if (currentCluster.length > 0) clusters.push(currentCluster);

        // For each cluster, simply divide vertical space?
        // User said "Average occupy". If 3 events overlap, 33% height each.
        // But if A overlaps B, and B overlaps C, but A doesn't overlap C? 
        // Then A and C could share a line.
        // "Average occupy" sounds like if ANY overlap in a group, split the height.

        clusters.forEach(cluster => {
            // Stacked Card Style
            // overlapping events are stacked vertically with offset
            const count = cluster.length;
            cluster.forEach((ev, idx) => {
                // Calculate dynamic top offset and height
                // To keep it contained but "stacked", we can use indices
                // Max offset shouldn't blow up the row. 
                // Let's cap offset at 40%?

                // Simple overlapping:
                // height: 80% (fixed large height)
                // top: idx * 15 % (offset)
                // zIndex: 10 + idx (stack order)

                processedEvents.push({
                    ...ev,
                    style: {
                        top: `${idx * 15}%`,
                        height: '80%',
                        zIndex: 10 + idx
                    }
                });
            });
        });
    }

    const isWeekend = date.getDay() === 0 || date.getDay() === 6;

    const handleGridClick = (e) => {
        const rect = e.currentTarget.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const width = rect.width;
        const ratio = x / width;
        const totalMinutes = 24 * 60;
        // Snap to nearest 10 minutes
        const rawMinutes = totalMinutes * ratio;
        const clickMinutes = Math.round(rawMinutes / 10) * 10;

        const clickDate = new Date(dayStart);
        clickDate.setHours(0, 0, 0, 0); // Reset to starts
        clickDate.setMinutes(clickMinutes);

        // Default 1 hour duration
        const end = addMinutes(clickDate, 60);

        if (onAddEvent) {
            onAddEvent(clickDate, end);
        }
    };

    return (
        <div
            id={`row-${format(date, 'yyyy-MM-dd')}`}
            className={`flex border-b border-gray-200 ${isWeekend ? 'bg-gray-50' : 'bg-white'} transition-colors duration-500`}
        >
            {/* Date Column */}
            <div className="w-24 flex-shrink-0 p-2 border-r border-gray-200 flex flex-col justify-center items-center text-center sticky left-0 z-20 bg-inherit shadow-[4px_0_24px_-12px_rgba(0,0,0,0.1)]">
                <span className="text-xs text-gray-500 font-medium">{format(date, 'EEE')}</span>
                <span className={`text-lg font-bold ${isWeekend ? 'text-gray-600' : 'text-blue-600'}`}>
                    {format(date, 'd MMM')}
                </span>
            </div>

            {/* Time Grid - Increased height to h-24 */}
            <div
                className="flex-grow relative h-24 min-w-[1200px] cursor-crosshair group"
                onClick={handleGridClick}
            >
                {/* Hour markers */}
                {Array.from({ length: 24 }).map((_, i) => (
                    <div
                        key={i}
                        className="absolute top-0 bottom-0 border-l border-gray-100 group-hover:border-gray-200 transition-colors"
                        style={{ left: `${(i / 24) * 100}%` }}
                    />
                ))}

                {/* Highlight Overlay */}
                {highlight && areIntervalsOverlapping(
                    { start: highlight.start, end: highlight.end },
                    { start: dayStart, end: dayEnd }
                ) && (() => {
                    // Calculate positioning for highlight
                    // If type is 'today', highlight whole day? Or detailed?
                    // User said "Today also add glowing border". 
                    // If highlight.type === 'today', maybe we highlight the whole row border?
                    // But if highlight.type === 'clash', we want specific time.

                    if (highlight.type === 'today') {
                        // For 'today', we might want to highlight the row container instead?
                        // But we can do it here too as an overlay or border.
                        // Let's return null here and handle 'today' via row className if preferred.
                        // Actually user said "Specific time highlighting (Conflict & Today)".
                        // For Today, usually "Current Time" line or just the whole day.
                        // User said "Today also add breathing border".
                        return (
                            <div className="absolute inset-0 border-4 border-blue-400 animate-pulse z-10 pointer-events-none rounded-sm shadow-[0_0_15px_rgba(96,165,250,0.7)]" />
                        );
                    }

                    // For clashes, specific time range clamped to this day
                    const hStart = max([highlight.start, dayStart]);
                    const hEnd = min([highlight.end, dayEnd]);

                    if (hEnd <= hStart) return null;

                    const totalMinutes = 24 * 60;
                    const startMinutes = (hStart.getHours() * 60) + hStart.getMinutes();
                    const endMinutes = (hEnd.getHours() * 60) + hEnd.getMinutes();
                    // Handle day crossing end (24:00)
                    const endMinutesFinal = (hEnd.getTime() === dayEnd.getTime() + 1) ? totalMinutes : endMinutes;

                    const left = (startMinutes / totalMinutes) * 100;
                    const width = ((endMinutesFinal - startMinutes) / totalMinutes) * 100;

                    return (
                        <div
                            className="absolute bg-red-500/20 border-2 border-red-500 z-10 pointer-events-none highlight-clash"
                            style={{
                                left: `${left}%`,
                                width: `${width}%`,
                                top: 0,
                                bottom: 0,
                                boxShadow: '0 0 15px rgba(239, 68, 68, 0.6)'
                            }}
                        />
                    );
                })()}

                {/* Events */}
                {processedEvents.map(event => (
                    <EventBlock
                        key={event.id}
                        event={event}
                        onClick={(e) => {
                            // Find original event to pass to handler, to avoid passing clamped times
                            const original = events.find(ev => ev.id === event.id);
                            onEventClick(original || event);
                        }}
                        style={event.style} // Pass calculated position
                    />
                ))}
            </div>
        </div>
    );
}
