import { MASSEY_COLORS } from '../utils/constants';
import { format } from 'date-fns';

/**
 * @param {Object} props
 * @param {import('../utils/constants').Event} props.event
 * @param {Function} props.onClick
 */
export default function EventBlock({ event, onClick, ...props }) {
    // Calculate width and position based on time
    // This component will likely be placed inside a relative container representing 24 hours.
    // 00:00 -> 0%, 24:00 -> 100%

    const startMinutes = event.start.getHours() * 60 + event.start.getMinutes();
    const endMinutes = event.end.getHours() * 60 + event.end.getMinutes();
    const durationMinutes = endMinutes - startMinutes;

    const totalDayMinutes = 24 * 60;

    const leftPercent = (startMinutes / totalDayMinutes) * 100;
    const widthPercent = (durationMinutes / totalDayMinutes) * 100;

    const colorClass = MASSEY_COLORS[event.colorId] || MASSEY_COLORS[0];

    return (
        <div
            onClick={(e) => { e.stopPropagation(); onClick(event); }}
            className={`absolute rounded-md shadow-sm border border-white/20 text-white text-xs overflow-hidden cursor-pointer hover:brightness-110 transition-all ${colorClass}`}
            style={{
                left: `${leftPercent}%`,
                width: `${widthPercent}%`,
                zIndex: 10,
                ...(props.style || { top: '4px', bottom: '4px' }) // Default or override
            }}
            title={`${event.title} (${format(event.start, 'HH:mm')} - ${format(event.end, 'HH:mm')})`}
        >
            <div className="px-1 py-0.5 truncate font-semibold">
                {event.title}
            </div>
            {durationMinutes > 45 && (
                <div className="px-1 truncate opacity-90 text-[10px]">
                    {event.note}
                </div>
            )}
        </div>
    );
}
