import { eachDayOfInterval, format } from 'date-fns';
import EventRow from './EventRow';

/**
 * @param {Object} props
 * @param {Date} props.startDate
 * @param {Date} props.endDate
 * @param {import('../utils/constants').Event[]} props.events
 * @param {Function} props.onEventClick
 */
export default function Timeline({ startDate, endDate, events, onEventClick, onAddEvent, highlight }) {
    const days = eachDayOfInterval({ start: startDate, end: endDate });

    return (
        <div className="flex flex-col h-full overflow-hidden bg-white border rounded-lg shadow-sm">
            {/* Header - Time Axis */}
            <div className="flex border-b border-gray-200 bg-gray-50 z-30">
                <div className="w-24 flex-shrink-0 border-r border-gray-200 p-2 bg-gray-50 sticky left-0 z-40">
                    {/* Empty corner */}
                </div>
                <div className="flex-grow relative h-8 min-w-[1200px] text-xs text-gray-400">
                    {Array.from({ length: 25 }).map((_, i) => (
                        <div
                            key={i}
                            className="absolute top-1 transform -translate-x-1/2"
                            style={{ left: `${(i / 24) * 100}%` }}
                        >
                            {i}:00
                        </div>
                    ))}
                </div>
            </div>

            {/* Body - Scrollable */}
            <div className="flex-grow overflow-auto relative">
                {days.map(day => (
                    <EventRow
                        key={day.toISOString()}
                        date={day}
                        events={events}
                        onEventClick={onEventClick}
                        onAddEvent={onAddEvent}
                        highlight={highlight}
                    />
                ))}
            </div>
        </div>
    );
}
