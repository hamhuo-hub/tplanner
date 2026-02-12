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
export default function Timeline({ startDate, endDate, events, onEventClick, onAddEvent, highlight, onLoadPrev, onLoadNext, onUpdateEvent, clashes }) {
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


    // --- Drag & Drop Logic ---
    const [dragState, setDragState] = useState(null); // Visual state for ghost rendering
    const dragDataRef = useRef({ status: 'idle', startX: 0, startY: 0, event: null, offsetMs: 0, latestSnap: null });
    const dragInteractionRef = useRef({ didMove: false });
    const eventsRef = useRef(events); // Ref to keep latest events for conflict checking

    // Keep events ref current
    useEffect(() => {
        eventsRef.current = events;
    }, [events]);

    const getTimeFromClient = (clientX, clientY) => {
        const targetElement = document.elementFromPoint(clientX, clientY);
        const dayRow = targetElement?.closest('[id^="row-"]');
        if (!dayRow) return null;

        const dateStr = dayRow.id.replace('row-', '');
        const rowDate = new Date(dateStr);
        const rowStartMs = rowDate.getTime(); // 00:00 of that day

        const gridContainer = dayRow.querySelector('.cursor-crosshair');
        if (!gridContainer) return null;

        const gridRect = gridContainer.getBoundingClientRect();
        const relativeX = clientX - gridRect.left;
        const width = gridRect.width;

        // Clamp 0 to width
        const clampedX = Math.max(0, Math.min(relativeX, width));
        const ratio = clampedX / width;

        const dayDurationMs = 24 * 60 * 60 * 1000;
        const timeWithinDayMs = ratio * dayDurationMs;

        return rowStartMs + timeWithinDayMs;
    };

    const handleDragStart = (event, clientX, clientY, date) => {
        dragInteractionRef.current.didMove = false;

        // Find the TRUE event from the source list (to avoid clamped start/end from EventRow)
        const trueEvent = events.find(e => e.id === event.id) || event;

        const mouseMs = getTimeFromClient(clientX, clientY);
        if (!mouseMs) return;

        // Offset = EventStart - MouseTime
        const eventStartMs = trueEvent.start.getTime();
        const offsetMs = eventStartMs - mouseMs;

        // Initialize Ref Data
        dragDataRef.current = {
            status: 'pending',
            startX: clientX,
            startY: clientY,
            event: trueEvent, // Use the real, full-duration event
            offsetMs, // Store MS offset
            latestSnap: null
        };

        // Attach global listeners directly
        window.addEventListener('mousemove', handleGlobalMouseMove);
        window.addEventListener('mouseup', handleGlobalMouseUp);
    };

    const calculateSnap = (currentMs, durationMs, offsetMs) => {
        // Raw New Start = Mouse + Offset
        const rawStartMs = currentMs + offsetMs;

        // Round to nearest 10 minutes
        const snapStepMs = 10 * 60 * 1000;
        const snappedStartMs = Math.round(rawStartMs / snapStepMs) * snapStepMs;

        const newStart = new Date(snappedStartMs);
        const newEnd = new Date(snappedStartMs + durationMs);

        return {
            snapStart: newStart,
            snapEnd: newEnd
        };
    };

    const handleGlobalMouseMove = (e) => {
        const data = dragDataRef.current;
        if (data.status === 'idle') return;

        if (data.status === 'pending') {
            const dx = Math.abs(e.clientX - data.startX);
            const dy = Math.abs(e.clientY - data.startY);

            if (dx > 5 || dy > 5) {
                // Threshold crossed - Initialize Drag State
                data.status = 'dragging';
                dragInteractionRef.current.didMove = true;

                const currentMs = getTimeFromClient(e.clientX, e.clientY);
                if (currentMs) {
                    const durationMs = data.event.end.getTime() - data.event.start.getTime();
                    const snap = calculateSnap(currentMs, durationMs, data.offsetMs);
                    data.latestSnap = snap;

                    setDragState({
                        event: data.event,
                        snapStart: snap.snapStart,
                        snapEnd: snap.snapEnd,
                        currentX: e.clientX,
                        currentY: e.clientY
                    });
                }
            }
        } else if (data.status === 'dragging') {
            const currentMs = getTimeFromClient(e.clientX, e.clientY);
            if (currentMs) {
                const durationMs = data.event.end.getTime() - data.event.start.getTime();
                const snap = calculateSnap(currentMs, durationMs, data.offsetMs);
                data.latestSnap = snap;

                setDragState(prev => {
                    if (!prev) return null;
                    return {
                        ...prev,
                        currentX: e.clientX,
                        currentY: e.clientY,
                        ...snap
                    };
                });
            }
        }
    };

    // Separate function for Drop Logic to avoid closure issues inside state updater
    const finalizeDrop = (event, snapStart, snapEnd) => {
        if (!snapStart || !snapEnd) return;

        const newStart = snapStart;
        const newEnd = snapEnd;
        const currentEvents = eventsRef.current; // Use Ref for latest events

        // Same ID conflict check (ignore self)
        const otherEvents = currentEvents.filter(e => e.id !== event.id);
        const overlaps = otherEvents.filter(e =>
            e.type !== 'status' &&
            newStart < e.end && newEnd > e.start
        );

        if (event.type === 'task') {
            const hitTask = overlaps.find(e => e.type === 'task');
            if (hitTask) {
                const overlapStart = new Date(Math.max(hitTask.start, newStart));
                const overlapEnd = new Date(Math.min(hitTask.end, newEnd));
                const overlapDuration = overlapEnd - overlapStart;

                if (overlapDuration <= 0) {
                    // If no actual overlap, just update the event
                    if (onUpdateEvent) {
                        onUpdateEvent([{ ...event, start: newStart, end: newEnd }]);
                    }
                    return;
                }

                const updates = [];
                updates.push({ ...event, start: newStart, end: newEnd });

                // Handle splitting the hitTask
                if (hitTask.start < overlapStart) {
                    // Part 1: Before overlap
                    updates.push({ ...hitTask, end: overlapStart });
                }

                // Part 2: After overlap (if any)
                if (hitTask.end > overlapEnd) {
                    const segment2Start = overlapEnd;
                    const segment2End = new Date(hitTask.end.getTime() + (newEnd.getTime() - newStart.getTime()) - (overlapEnd.getTime() - overlapStart.getTime())); // Adjust end based on new event's duration
                    updates.push({ ...hitTask, id: crypto.randomUUID(), start: segment2Start, end: segment2End });
                }

                if (onUpdateEvent) onUpdateEvent(updates);
                return;
            }
        }

        if (onUpdateEvent) {
            onUpdateEvent([{ ...event, start: newStart, end: newEnd }]);
        }
    };

    const handleGlobalMouseUp = () => {
        const data = dragDataRef.current;

        // Clean up listeners
        window.removeEventListener('mousemove', handleGlobalMouseMove);
        window.removeEventListener('mouseup', handleGlobalMouseUp);

        if (data.status === 'dragging' && data.event && data.latestSnap) {
            // Perform Drop using the latest snapped position stored in ref
            finalizeDrop(data.event, data.latestSnap.snapStart, data.latestSnap.snapEnd);

            // OPTIMISTIC UPDATE:
            // Don't clear state immediately. Mark as dropping.
            // This renders the Ghost for one more frame (or until events update), covering the gap.
            setDragState(prev => prev ? ({ ...prev, isDropping: true }) : null);
        } else {
            // Not dragging (just a click or minimal move), clear immediately
            setDragState(null);
        }

        // Reset ref
        dragDataRef.current = { status: 'idle', startX: 0, startY: 0, event: null, offsetMs: 0, latestSnap: null };

        // Defer clearing click block
        if (data.status === 'dragging') {
            dragInteractionRef.current.lastDragTime = Date.now();
        }
    };

    // Effect to clear drag state after drop creates new events
    useEffect(() => {
        if (dragState?.isDropping) {
            setDragState(null);
        }
    }, [events]); // Run when events update

    // Cleanup on unmount just in case
    useEffect(() => {
        return () => {
            window.removeEventListener('mousemove', handleGlobalMouseMove);
            window.removeEventListener('mouseup', handleGlobalMouseUp);
        };
    }, []);

    // Proxy onEventClick to block after drag
    const handleEventClickProxy = (e) => {
        // Block if we just finished dragging (within 200ms)
        const timeSinceDrag = Date.now() - (dragInteractionRef.current.lastDragTime || 0);
        if (timeSinceDrag < 200) {
            return;
        }
        onEventClick(e);
    };

    // Proxy onAddEvent to block after drag (same protection)
    const handleAddEventProxy = (...args) => {
        const timeSinceDrag = Date.now() - (dragInteractionRef.current.lastDragTime || 0);
        if (timeSinceDrag < 200) {
            return;
        }
        onAddEvent(...args);
    };

    return (
        <div className="timeline-root flex flex-col h-full overflow-hidden bg-white border rounded-lg shadow-sm relative">
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
                className="timeline-scroll-area flex-grow overflow-auto relative select-none" // Disable text selection during drag
                onScroll={handleScroll}
                ref={scrollContainerRef}
            >
                {days.map(day => (
                    <EventRow
                        key={day.toISOString()}
                        date={day}
                        events={events}
                        clashes={clashes}
                        onEventClick={handleEventClickProxy} // Use Proxy
                        onAddEvent={handleAddEventProxy} // Use Proxy
                        highlight={highlight}
                        onDragStart={handleDragStart} // Pass handler
                        dragState={dragState} // Pass drag state for ghost rendering
                    />
                ))}
            </div>

            {/* Removed Floating Ghost Overlay - Rendering in EventRow now */}
        </div>
    );
}

// Helper to access colors
// import { MASSEY_COLORS } from '../utils/constants'; // Not needed here anymore if ghost is in EventRow
