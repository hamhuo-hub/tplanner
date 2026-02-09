import { eachDayOfInterval } from 'date-fns';
import EventRow from './EventRow';
import { useRef, useEffect, useLayoutEffect, useState } from 'react';

/**
 * @param {Object} props
 * @param {Date} props.startDate
 * @param {Date} props.endDate
 * @param {import('../utils/constants').Event[]} props.events
 * @param {Function} props.onEventClick
 * @param {Function} props.onAddEvent
 * @param {Object} highlight
 * @param {Function} onLoadPrev
 * @param {Function} onLoadNext
 */
export default function Timeline({ startDate, endDate, events, onEventClick, onAddEvent, highlight, onLoadPrev, onLoadNext }) {
    const scrollContainerRef = useRef(null);
    const [days, setDays] = useState([]);

    // Track previous start date to adjust scroll
    const prevStartDateRef = useRef(startDate);

    useEffect(() => {
        if (!startDate || !endDate) return;
        setDays(eachDayOfInterval({ start: startDate, end: endDate }));
    }, [startDate, endDate]);

    const [previousScrollHeight, setPreviousScrollHeight] = useState(0);

    const handleScroll = (e) => {
        const { scrollTop, scrollHeight, clientHeight } = e.target;

        // Threshold for loading more days
        if (scrollTop < 50) {
            // We are near top
            setPreviousScrollHeight(scrollHeight);
            if (onLoadPrev) onLoadPrev();
        }

        if (scrollTop + clientHeight >= scrollHeight - 50) {
            // We are near bottom
            if (onLoadNext) onLoadNext();
        }
    };

    useLayoutEffect(() => {
        if (!scrollContainerRef.current) return;

        // Only adjust scroll if explicitly allowed (e.g., during infinite scroll up)
        // If we are jumping/resetting view, we do NOT want this adjustment.
        // We can infer this: if previousScrollHeight is 0, we probably didn't scroll.
        // But better to use a prop or state? 
        // Actually, previousScrollHeight is set in handleScroll just before onLoadPrev.
        // So checking previousScrollHeight > 0 is a good proxy!

        const container = scrollContainerRef.current;
        if (prevStartDateRef.current && startDate < prevStartDateRef.current && previousScrollHeight > 0) {
            const newScrollHeight = container.scrollHeight;
            const diff = newScrollHeight - previousScrollHeight;

            if (diff > 0) {
                container.scrollTop += diff;
            }
            // Reset to avoid double correction
            setPreviousScrollHeight(0);
        }
        prevStartDateRef.current = startDate;
    }, [days, startDate, previousScrollHeight]);


    return (
        <div className="timeline-root flex flex-col h-full overflow-hidden bg-white border rounded-lg shadow-sm">
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
            <div
                className="timeline-scroll-area flex-grow overflow-auto relative"
                onScroll={handleScroll}
                ref={scrollContainerRef}
            >
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
