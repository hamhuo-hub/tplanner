import { MASSEY_COLORS } from '../utils/constants';
import { format } from 'date-fns';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Event} props.event
 * @param {Function} props.onClick
 */
export default function EventBlock({ event, onClick, isConflicting, ...props }) {
    // Calculate width and position based on time
    // This component will likely be placed inside a relative container representing 24 hours.
    // 00:00 -> 0%, 24:00 -> 100%

    const startMinutes = event.start.getHours() * 60 + event.start.getMinutes();
    const endMinutes = event.end.getHours() * 60 + event.end.getMinutes();
    const durationMinutes = endMinutes - startMinutes;

    const totalDayMinutes = 24 * 60;

    const leftPercent = (startMinutes / totalDayMinutes) * 100;
    const widthPercent = (durationMinutes / totalDayMinutes) * 100;

    const colorClass = MASSEY_COLORS[event.colorId] || 'bg-blue-600';

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
            className={`event-block absolute rounded shadow-md border border-white/20 text-white text-xs overflow-hidden cursor-pointer hover:brightness-110 transition-all ${colorClass} ${isConflicting ? 'brightness-[0.75] saturate-150 border-white/40 ring-1 ring-black/10' : ''}`}
            style={{
                left: `${leftPercent}%`,
                width: `${widthPercent}%`,
                zIndex: 10,
                ...(props.style || { top: '4px', bottom: '4px' }) // Default or override
            }}
            title={`${event.title} (${format(event.start, 'HH:mm')} - ${format(event.end, 'HH:mm')})`}
        >
            <div className="px-2 py-1 h-full flex flex-col">
                <div className="font-bold truncate leading-tight">
                    {event.title}
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
