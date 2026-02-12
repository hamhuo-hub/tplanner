import { format, areIntervalsOverlapping, startOfDay, endOfDay, max, min, addMinutes, isSameDay } from 'date-fns';
import EventBlock from './EventBlock';
import { useTranslation } from 'react-i18next';
import { getDateLocale } from '../utils/dateLocale';
import { MASSEY_COLORS } from '../utils/constants';

/**
 * @param {Object} props
 * @param {Date} props.date
 * @param {import('../utils/constants').Event[]} props.events
 * @param {Function} props.onEventClick
 * @param {Function} props.onAddEvent
 * @param {Object} dragState
 */
export default function EventRow({ date, events, onEventClick, onAddEvent, highlight, onDragStart, dragState }) {
    const { i18n } = useTranslation();
    const locale = getDateLocale(i18n.language);

    const dayStart = startOfDay(date);
    const dayEnd = endOfDay(date);

    // Filter events for this day
    const dayEventsRaw = events.filter(e =>
        areIntervalsOverlapping(
            { start: e.start, end: e.end },
            { start: dayStart, end: dayEnd }
        )
    );

    // Split into Status and Regular Events
    const statusEvents = dayEventsRaw.filter(e => e.type === 'status');
    const regularEvents = dayEventsRaw.filter(e => e.type !== 'status'); // Show everything else as regular events

    // --- Process Regular Events (Existing Logic) ---
    const processedRegularEvents = regularEvents.map(e => {
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

    const finalRegularEvents = [];
    if (processedRegularEvents.length > 0) {
        const clusters = [];
        let currentCluster = [];
        let clusterEnd = null;

        processedRegularEvents.forEach(event => {
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

        clusters.forEach(cluster => {
            const isConflicting = cluster.length > 1;
            cluster.forEach((ev, idx) => {
                finalRegularEvents.push({
                    ...ev,
                    isConflicting,
                    style: {
                        top: `${idx * 15}%`,
                        height: '80%',
                        zIndex: 10 + idx
                    }
                });
            });
        });
    }

    // --- Process Status Events (Stacking Logic) ---
    const processedStatusEvents = statusEvents.map(e => {
        const clampedStart = max([e.start, dayStart]);
        const clampedEnd = min([e.end, dayEnd]);
        return {
            ...e,
            start: clampedStart,
            end: clampedEnd
        };
    }).sort((a, b) => a.start - b.start);

    const statusRows = [];
    const finalStatusEvents = processedStatusEvents.map(ev => {
        // Find first row where this event fits
        let rowIndex = 0;
        while (true) {
            const row = statusRows[rowIndex] || [];
            const collision = row.find(existing => areIntervalsOverlapping(
                { start: existing.start, end: existing.end },
                { start: ev.start, end: ev.end }
            ));
            if (!collision) {
                if (!statusRows[rowIndex]) statusRows[rowIndex] = [];
                statusRows[rowIndex].push(ev);
                break;
            }
            rowIndex++;
        }
        return {
            ...ev,
            rowIndex
        };
    });


    const isWeekend = date.getDay() === 0 || date.getDay() === 6;

    const handleGridClick = (e) => {
        // Prevent clicking on grid if we actually clicked an event (safety check)
        if (e.target.closest('.event-block')) return;

        const rect = e.currentTarget.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const width = rect.width;
        const ratio = x / width;
        const totalMinutes = 24 * 60;
        const rawMinutes = totalMinutes * ratio;
        const clickMinutes = Math.round(rawMinutes / 10) * 10;

        const clickDate = new Date(dayStart);
        clickDate.setHours(0, 0, 0, 0);
        clickDate.setMinutes(clickMinutes);
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
                <span className="text-xs text-gray-500 font-medium">{format(date, 'EEE', { locale })}</span>
                <span className={`text-lg font-bold ${isWeekend ? 'text-gray-600' : 'text-blue-600'}`}>
                    {format(date, 'd MMM', { locale })}
                </span>
            </div>

            {/* Main Grid Container */}
            <div
                className="flex-grow relative h-32 min-w-[1200px] cursor-crosshair group flex flex-col"
                onClick={handleGridClick}
            >
                {/* Status Zone (Top ~15%) */}
                {statusEvents.length > 0 && (
                    <div className="absolute top-0 left-0 right-0 h-[15%] z-30 pointer-events-none">
                        {finalStatusEvents.map(ev => {
                            const totalMinutes = 24 * 60;
                            const startMinutes = (ev.start.getHours() * 60) + ev.start.getMinutes();
                            const endMinutes = (ev.end.getHours() * 60) + ev.end.getMinutes();
                            const endMinutesFinal = (ev.end.getTime() === dayEnd.getTime() + 1) ? totalMinutes : endMinutes;

                            const left = (startMinutes / totalMinutes) * 100;
                            const width = ((endMinutesFinal - startMinutes) / totalMinutes) * 100;

                            return (
                                <div
                                    key={ev.id}
                                    className={`absolute rounded px-1 text-[10px] whitespace-nowrap overflow-hidden text-white pointer-events-auto cursor-pointer shadow-sm hover:brightness-110 ${ev.colorId !== undefined ? MASSEY_COLORS[ev.colorId] : 'bg-gray-500'}`}
                                    style={{
                                        left: `${left}%`,
                                        width: `${width}%`,
                                        top: `${ev.rowIndex * 18}px`,
                                        height: '16px',
                                        lineHeight: '16px'
                                    }}
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        // Find original to pass all props
                                        const original = events.find(original => original.id === ev.id);
                                        onEventClick(original || ev);
                                    }}
                                >
                                    {ev.title}
                                </div>
                            );
                        })}
                    </div>
                )}

                {/* Regular Event Zone (Bottom ~85%) */}
                <div className="relative flex-grow h-full">
                    {/* Hour markers */}
                    {Array.from({ length: 24 }).map((_, i) => (
                        <div
                            key={i}
                            className="absolute top-0 bottom-0 border-l border-gray-100 group-hover:border-gray-200 transition-colors"
                            style={{ left: `${(i / 24) * 100}%` }}
                        />
                    ))}

                    {/* Highlight Overlay - Clashes */}
                    {highlight && areIntervalsOverlapping(
                        { start: highlight.start, end: highlight.end },
                        { start: dayStart, end: dayEnd }
                    ) && highlight.type !== 'today' && (() => {
                        const hStart = max([highlight.start, dayStart]);
                        const hEnd = min([highlight.end, dayEnd]);

                        if (hEnd <= hStart) return null;

                        const totalMinutes = 24 * 60;
                        const startMinutes = (hStart.getHours() * 60) + hStart.getMinutes();
                        const endMinutes = (hEnd.getHours() * 60) + hEnd.getMinutes();
                        const endMinutesFinal = (hEnd.getTime() === dayEnd.getTime() + 1) ? totalMinutes : endMinutes;

                        const left = (startMinutes / totalMinutes) * 100;
                        const width = ((endMinutesFinal - startMinutes) / totalMinutes) * 100;

                        return (
                            <div
                                className="absolute bg-red-500/20 border-2 border-red-500 z-10 pointer-events-none highlight-clash"
                                style={{
                                    left: `${left}%`,
                                    width: `${width}%`,
                                    top: '15%',
                                    bottom: 0,
                                    boxShadow: '0 0 15px rgba(239, 68, 68, 0.6)'
                                }}
                            />
                        );
                    })()}

                    {/* Today Highlight Border */}
                    {highlight && highlight.type === 'today' && areIntervalsOverlapping(
                        { start: highlight.start, end: highlight.end },
                        { start: dayStart, end: dayEnd }
                    ) && (
                            <div className="absolute inset-0 border-4 border-blue-400 animate-pulse z-10 pointer-events-none rounded-sm shadow-[0_0_15px_rgba(96,165,250,0.7)]" />
                        )}


                    {/* Regular Events */}
                    {finalRegularEvents.map(event => {
                        // If this event is being dragged, hide it (it's being represented by the ghost or elsewhere)
                        const isDragging = dragState?.event?.id === event.id;

                        return (
                            <EventBlock
                                key={event.id}
                                event={event}
                                isConflicting={event.isConflicting}
                                onClick={(e) => {
                                    const original = events.find(ev => ev.id === event.id);
                                    onEventClick(original || event);
                                }}
                                onDragStart={(e) => onDragStart(event, e.clientX, e.clientY, date)}
                                style={{
                                    ...event.style,
                                    top: `calc(15% + ${event.style.top})`,
                                    height: '70%',
                                    opacity: isDragging ? 0 : 1, // Hide original when dragging
                                    pointerEvents: isDragging ? 'none' : 'auto'
                                }}
                            />
                        );
                    })}

                    {/* Ghost Event (Snap Preview) */}
                    {dragState && dragState.snapStart && dragState.snapEnd && (
                        (() => {
                            const dayStart = startOfDay(date);
                            const dayEnd = endOfDay(date);
                            const snapStart = dragState.snapStart;
                            const snapEnd = dragState.snapEnd;

                            // Check overlap
                            if (!areIntervalsOverlapping({ start: dayStart, end: dayEnd }, { start: snapStart, end: snapEnd })) {
                                return null;
                            }

                            // Calculate position within this day
                            const rangeStart = max([dayStart, snapStart]);
                            const rangeEnd = min([dayEnd, snapEnd]);

                            const totalDayMins = 24 * 60;
                            const startMins = rangeStart.getHours() * 60 + rangeStart.getMinutes();

                            // Handle cross-day end time (e.g. 00:00 of next day needs to be 1440 mins)
                            let endMins = rangeEnd.getHours() * 60 + rangeEnd.getMinutes();
                            if (endMins === 0 && rangeEnd > rangeStart) endMins = totalDayMins;

                            const leftPercent = (startMins / totalDayMins) * 100;
                            const widthPercent = ((endMins - startMins) / totalDayMins) * 100;

                            return (
                                <EventBlock
                                    event={{
                                        ...dragState.event,
                                        start: snapStart,
                                        end: snapEnd
                                    }}
                                    isConflicting={false}
                                    onClick={() => { }}
                                    style={{
                                        left: `${leftPercent}%`,
                                        width: `${widthPercent}%`,
                                        top: '15%',
                                        height: '70%',
                                        zIndex: 50,
                                        opacity: 0.8,
                                        border: '2px dashed white',
                                        boxShadow: '0 4px 12px rgba(0,0,0,0.2)',
                                        position: 'absolute'
                                    }}
                                />
                            );
                        })()
                    )}
                </div>
            </div>
        </div>
    );
}
