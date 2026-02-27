import { MASSEY_COLORS } from '../utils/constants';
import { format } from 'date-fns';
import { formatInTimeZone } from 'date-fns-tz';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Event} props.event
 * @param {Function} props.onClick
 */
export default function EventBlock({ event, onClick, isConflicting, displayTimezone, ...props }) {
    // Determine effective timezone name for calculations
    const tz = displayTimezone || Intl.DateTimeFormat().resolvedOptions().timeZone;

    // We parse the exact time in the target display timezone and compute minutes
    // This aligns the visual block to the numbers printed on the Timeline axis.
    const extractMinutes = (dateStr) => {
        const parts = dateStr.split(':');
        return parseInt(parts[0], 10) * 60 + parseInt(parts[1], 10);
    };

    let startMinutes, endMinutes;
    try {
        const startTzTime = formatInTimeZone(event.start, tz, 'HH:mm');
        const endTzTime = formatInTimeZone(event.end, tz, 'HH:mm');

        startMinutes = extractMinutes(startTzTime);
        endMinutes = extractMinutes(endTzTime);

        // Handle midnight crossover if end time wraps to next day logically but is actually a duration
        if (endMinutes < startMinutes) {
            endMinutes += 24 * 60;
        }

        // Extremely short events (e.g. 0 duration) should have a minimal visual width
        if (endMinutes - startMinutes < 15) {
            endMinutes = startMinutes + 15;
        }
    } catch (e) {
        // Fallback to local timezone calculation
        startMinutes = event.start.getHours() * 60 + event.start.getMinutes();
        endMinutes = event.end.getHours() * 60 + event.end.getMinutes();
        if (endMinutes < startMinutes) endMinutes += 24 * 60;
    }

    const durationMinutes = endMinutes - startMinutes;
    const totalDayMinutes = 24 * 60;

    const leftPercent = (startMinutes / totalDayMinutes) * 100;
    const widthPercent = (durationMinutes / totalDayMinutes) * 100;

    const isCompleted = event.completed === true;
    const color = isCompleted ? '#9CA3AF' : (MASSEY_COLORS[event.colorId] || MASSEY_COLORS[0]); // Gray-400 equivalent for completed
    const titleOffsetPx = event.titleOffsetPx || 0;

    return (
        <div
            onClick={(e) => { e.stopPropagation(); onClick(event); }}
            onMouseDown={(e) => {
                // Only left click
                if (e.button !== 0) return;
                e.stopPropagation();
                if (props.onDragStart) {
                    props.onDragStart(e);
                }
            }}
            className={`event-block absolute rounded shadow-md border overflow-hidden cursor-pointer hover:brightness-110 transition-all ${isConflicting ? 'border-white/40 ring-1 ring-black/10 saturate-150 brightness-[0.75]' : 'border-white/20'} ${isCompleted ? 'opacity-80 line-through' : 'text-white'}`}
            style={{
                backgroundColor: color,
                left: `${leftPercent}%`,
                width: `${widthPercent}%`,
                zIndex: 10,
                color: 'white',
                fontSize: '0.75rem',
                ...(props.style || { top: '4px', bottom: '4px' }) // Default or override
            }}
            title={`${event.title} (${formatInTimeZone(event.start, displayTimezone, 'HH:mm')} - ${formatInTimeZone(event.end, displayTimezone, 'HH:mm')})`}
        >
            <div
                className="px-2 h-full flex flex-col transition-all duration-300"
                style={{ paddingTop: `calc(0.25rem + ${titleOffsetPx}px)` }}
            >
                <div className="flex items-start">
                    {event.type === 'task' && (
                        <div
                            className="mr-1 mt-0.5 cursor-pointer flex-shrink-0 relative w-3 h-3 border border-white rounded-sm bg-white/10 hover:bg-white/30 flex items-center justify-center transition-colors"
                            onClick={(e) => {
                                e.stopPropagation();
                                if (props.onToggleTaskComplete) {
                                    props.onToggleTaskComplete(event.id, !isCompleted);
                                }
                            }}
                        >
                            {isCompleted && (
                                <svg className="w-2.5 h-2.5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                                </svg>
                            )}
                        </div>
                    )}
                    <div className="font-bold truncate leading-tight flex-grow">
                        {event.title}
                    </div>
                </div>
                {durationMinutes > 45 && event.note && (
                    <div className="truncate opacity-90 text-[10px] mt-0.5">
                        {event.note}
                    </div>
                )}
            </div>
        </div>
    );
}
