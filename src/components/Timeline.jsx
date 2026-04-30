import { eachDayOfInterval } from 'date-fns';
import { formatInTimeZone } from 'date-fns-tz';
import EventRow from './EventRow';
import { useRef, useEffect, useLayoutEffect, useState } from 'react';

export default function Timeline({ startDate, endDate, events, onEventClick, onAddEvent, highlight, onLoadPrev, onLoadNext, onUpdateEvent, clashes, travelTimezone, onToggleTaskComplete }) {
    const scrollContainerRef = useRef(null);
    const [days, setDays] = useState([]);
    const prevStartDateRef = useRef(startDate);

    useEffect(() => {
        if (!startDate || !endDate) return;
        setDays(eachDayOfInterval({ start: startDate, end: endDate }));
    }, [startDate, endDate]);

    const [previousScrollHeight, setPreviousScrollHeight] = useState(0);

    const handleScroll = (e) => {
        const { scrollTop, scrollHeight, clientHeight } = e.target;
        if (scrollTop < 50) {
            setPreviousScrollHeight(scrollHeight);
            if (onLoadPrev) onLoadPrev();
        }
        if (scrollTop + clientHeight >= scrollHeight - 50) {
            if (onLoadNext) onLoadNext();
        }
    };

    useLayoutEffect(() => {
        if (!scrollContainerRef.current) return;
        const container = scrollContainerRef.current;
        if (prevStartDateRef.current && startDate < prevStartDateRef.current && previousScrollHeight > 0) {
            const diff = container.scrollHeight - previousScrollHeight;
            if (diff > 0) container.scrollTop += diff;
            setPreviousScrollHeight(0);
        }
        prevStartDateRef.current = startDate;
    }, [days, startDate, previousScrollHeight]);

    // ── Drag & Drop ──────────────────────────────────────────────────────────
    const [dragState, setDragState] = useState(null);
    const dragDataRef = useRef({ status: 'idle', startX: 0, startY: 0, event: null, offsetMs: 0, latestSnap: null });
    const dragInteractionRef = useRef({ didMove: false });
    const eventsRef = useRef(events);
    useEffect(() => { eventsRef.current = events; }, [events]);

    const getTimeFromClient = (clientX, clientY) => {
        const targetElement = document.elementFromPoint(clientX, clientY);
        const dayRow = targetElement?.closest('[id^="row-"]');
        if (!dayRow) return null;
        const dateStr = dayRow.id.replace('row-', '');
        const rowDate = new Date(dateStr);
        const gridContainer = dayRow.querySelector('.event-row-grid');
        if (!gridContainer) return null;
        const gridRect = gridContainer.getBoundingClientRect();
        const clampedX = Math.max(0, Math.min(clientX - gridRect.left, gridRect.width));
        return rowDate.getTime() + (clampedX / gridRect.width) * 24 * 60 * 60 * 1000;
    };

    const handleDragStart = (event, clientX, clientY) => {
        dragInteractionRef.current.didMove = false;
        const trueEvent = events.find(e => e.id === event.id) || event;
        const mouseMs = getTimeFromClient(clientX, clientY);
        if (!mouseMs) return;
        dragDataRef.current = {
            status: 'pending', startX: clientX, startY: clientY,
            event: trueEvent, offsetMs: trueEvent.start.getTime() - mouseMs, latestSnap: null
        };
        window.addEventListener('mousemove', handleGlobalMouseMove);
        window.addEventListener('mouseup', handleGlobalMouseUp);
    };

    const calculateSnap = (currentMs, durationMs, offsetMs) => {
        const snapStepMs = 10 * 60 * 1000;
        const snappedStartMs = Math.round((currentMs + offsetMs) / snapStepMs) * snapStepMs;
        return { snapStart: new Date(snappedStartMs), snapEnd: new Date(snappedStartMs + durationMs) };
    };

    const handleGlobalMouseMove = (e) => {
        const data = dragDataRef.current;
        if (data.status === 'idle') return;
        if (data.status === 'pending') {
            if (Math.abs(e.clientX - data.startX) > 5 || Math.abs(e.clientY - data.startY) > 5) {
                data.status = 'dragging';
                dragInteractionRef.current.didMove = true;
                const currentMs = getTimeFromClient(e.clientX, e.clientY);
                if (currentMs) {
                    const snap = calculateSnap(currentMs, data.event.end - data.event.start, data.offsetMs);
                    data.latestSnap = snap;
                    setDragState({ event: data.event, ...snap, currentX: e.clientX, currentY: e.clientY });
                }
            }
        } else if (data.status === 'dragging') {
            const currentMs = getTimeFromClient(e.clientX, e.clientY);
            if (currentMs) {
                const snap = calculateSnap(currentMs, data.event.end - data.event.start, data.offsetMs);
                data.latestSnap = snap;
                setDragState(prev => prev ? ({ ...prev, ...snap, currentX: e.clientX, currentY: e.clientY }) : null);
            }
        }
    };

    const finalizeDrop = (event, snapStart, snapEnd) => {
        if (!snapStart || !snapEnd) return;
        const otherEvents = eventsRef.current.filter(e => e.id !== event.id);
        const overlaps = otherEvents.filter(e => e.type !== 'status' && snapStart < e.end && snapEnd > e.start);
        if (event.type === 'task') {
            const hitTask = overlaps.find(e => e.type === 'task');
            if (hitTask) {
                const overlapStart = new Date(Math.max(hitTask.start, snapStart));
                const overlapEnd   = new Date(Math.min(hitTask.end, snapEnd));
                const overlapDuration = overlapEnd - overlapStart;
                if (overlapDuration <= 0) { onUpdateEvent?.([{ ...event, start: snapStart, end: snapEnd }]); return; }
                const updates = [{ ...event, start: snapStart, end: snapEnd }];
                if (hitTask.start < overlapStart) updates.push({ ...hitTask, end: overlapStart });
                if (hitTask.end > overlapEnd) {
                    updates.push({ ...hitTask, id: crypto.randomUUID(), start: overlapEnd, end: new Date(hitTask.end.getTime() + (snapEnd - snapStart) - (overlapEnd - overlapStart)) });
                }
                onUpdateEvent?.(updates);
                return;
            }
        }
        onUpdateEvent?.([{ ...event, start: snapStart, end: snapEnd }]);
    };

    const handleGlobalMouseUp = () => {
        const data = dragDataRef.current;
        window.removeEventListener('mousemove', handleGlobalMouseMove);
        window.removeEventListener('mouseup', handleGlobalMouseUp);
        if (data.status === 'dragging' && data.event && data.latestSnap) {
            finalizeDrop(data.event, data.latestSnap.snapStart, data.latestSnap.snapEnd);
            setDragState(prev => prev ? ({ ...prev, isDropping: true }) : null);
        } else {
            setDragState(null);
        }
        dragDataRef.current = { status: 'idle', startX: 0, startY: 0, event: null, offsetMs: 0, latestSnap: null };
        if (data.status === 'dragging') dragInteractionRef.current.lastDragTime = Date.now();
    };

    useEffect(() => { if (dragState?.isDropping) setDragState(null); }, [events]);
    useEffect(() => () => {
        window.removeEventListener('mousemove', handleGlobalMouseMove);
        window.removeEventListener('mouseup', handleGlobalMouseUp);
    }, []);

    const handleEventClickProxy = (e) => {
        if (Date.now() - (dragInteractionRef.current.lastDragTime || 0) < 200) return;
        onEventClick(e);
    };
    const handleAddEventProxy = (...args) => {
        if (Date.now() - (dragInteractionRef.current.lastDragTime || 0) < 200) return;
        onAddEvent(...args);
    };

    return (
        <div className="timeline-root">
            {/* Time Axis Header */}
            <div className="timeline-header">
                <div className="timeline-header-label">
                    <span title={travelTimezone || 'Local Time'}>
                        {travelTimezone
                            ? (travelTimezone === 'Asia/Shanghai' ? 'BEIJING' : travelTimezone.split('/').pop().replace(/_/g, ' ').toUpperCase())
                            : 'LOCAL'}
                    </span>
                </div>
                <div className="timeline-header-axis" style={{ position: 'relative', minWidth: 1200 }}>
                    {Array.from({ length: 25 }).map((_, i) => {
                        // The axis always represents hours 0–23 in the display timezone.
                        // Each EventRow row = one day in that timezone, and positions
                        // inside the row are computed via formatInTimeZone in EventBlock.
                        const hourText = `${String(i).padStart(2, '0')}:00`;
                        return (
                            <div key={i} className="timeline-hour-tick" style={{ left: `${(i / 24) * 100}%` }}>
                                {hourText}
                            </div>
                        );
                    })}
                </div>
            </div>

            {/* Scrollable Body */}
            <div className="timeline-scroll-area" onScroll={handleScroll} ref={scrollContainerRef}>
                {days.map(day => (
                    <EventRow
                        key={day.toISOString()}
                        date={day}
                        events={events}
                        clashes={clashes}
                        onEventClick={handleEventClickProxy}
                        onAddEvent={handleAddEventProxy}
                        highlight={highlight}
                        onDragStart={handleDragStart}
                        dragState={dragState}
                        displayTimezone={travelTimezone || 'Asia/Shanghai'}
                        onToggleTaskComplete={onToggleTaskComplete}
                    />
                ))}
            </div>
        </div>
    );
}
