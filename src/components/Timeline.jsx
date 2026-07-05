import { eachDayOfInterval, format } from 'date-fns';
import { formatInTimeZone, fromZonedTime } from 'date-fns-tz';
import EventRow from './EventRow';
import { useRef, useEffect, useLayoutEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

export default function Timeline({ startDate, endDate, events, onEventClick, onAddEvent, highlight, onLoadPrev, onLoadNext, onUpdateEvent, clashes, travelTimezone, onToggleTaskComplete, journals, onSaveJournal, onContextMenu, selectedIds, onSelectionChange }) {
    const { t } = useTranslation();
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
        // 必须用与 EventBlock 渲染时相同的 displayTimezone 计算「行起点」，
        // 否则拖拽落点会相对于渲染位置偏移一个时区差（例如 UTC+8 vs UTC+12 → 偏移 4 小时）。
        const tz = travelTimezone || 'Asia/Shanghai';
        const rowDate = fromZonedTime(`${dateStr}T00:00:00`, tz);
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
                // Suppress browser text selection during drag
                document.body.style.userSelect = 'none';
                window.getSelection()?.removeAllRanges();
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
        onUpdateEvent?.([{ ...event, start: snapStart, end: snapEnd }]);
    };

    const handleGlobalMouseUp = () => {
        const data = dragDataRef.current;
        window.removeEventListener('mousemove', handleGlobalMouseMove);
        window.removeEventListener('mouseup', handleGlobalMouseUp);
        document.body.style.userSelect = '';
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

    // ── Box selection (rubber-band) ─────────────────────────────────────────
    // Surface-level patch for batch ops on recurring-task instances that
    // aren't sync-linked yet — lets users drag a box over several events and
    // delete them together instead of one at a time. Box is rendered in
    // viewport (fixed) coords so it stays correct while the row list scrolls.
    const [selectionBox, setSelectionBox] = useState(null); // {x1,y1,x2,y2}
    const selectionDataRef = useRef({ active: false, startX: 0, startY: 0 });
    const SELECT_DRAG_THRESHOLD = 4;

    const handleSelectionMouseDown = (e) => {
        if (e.button !== 0) return;
        if (e.target.closest('.event-block')) return;       // let event-drag handle this
        if (e.target.closest('.event-row-date')) return;    // journal column
        selectionDataRef.current = { active: false, startX: e.clientX, startY: e.clientY };
        window.addEventListener('mousemove', handleSelectionMouseMove);
        window.addEventListener('mouseup', handleSelectionMouseUp);
    };

    const handleSelectionMouseMove = (e) => {
        const data = selectionDataRef.current;
        const dx = e.clientX - data.startX, dy = e.clientY - data.startY;
        if (!data.active) {
            if (Math.abs(dx) < SELECT_DRAG_THRESHOLD && Math.abs(dy) < SELECT_DRAG_THRESHOLD) return;
            data.active = true;
            document.body.style.userSelect = 'none';
            window.getSelection()?.removeAllRanges();
        }
        data.x2 = e.clientX;
        data.y2 = e.clientY;
        setSelectionBox({ x1: data.startX, y1: data.startY, x2: e.clientX, y2: e.clientY });
    };

    const handleSelectionMouseUp = () => {
        window.removeEventListener('mousemove', handleSelectionMouseMove);
        window.removeEventListener('mouseup', handleSelectionMouseUp);
        document.body.style.userSelect = '';
        const data = selectionDataRef.current;
        setSelectionBox(null);
        if (data.active) {
            const left = Math.min(data.startX, data.x2), right = Math.max(data.startX, data.x2);
            const top = Math.min(data.startY, data.y2), bottom = Math.max(data.startY, data.y2);
            const hitIds = Array.from(document.querySelectorAll('.event-block[data-event-id]')).filter(el => {
                const r = el.getBoundingClientRect();
                return r.left < right && r.right > left && r.top < bottom && r.bottom > top;
            }).map(el => el.dataset.eventId);
            onSelectionChange?.(new Set(hitIds));
            dragInteractionRef.current.lastDragTime = Date.now(); // suppress the trailing click-to-add-event
        } else {
            onSelectionChange?.(new Set());
        }
        selectionDataRef.current = { active: false, startX: 0, startY: 0 };
    };

    useEffect(() => () => {
        window.removeEventListener('mousemove', handleSelectionMouseMove);
        window.removeEventListener('mouseup', handleSelectionMouseUp);
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
                    <span title={travelTimezone || t('timeline.localTime')}>
                        {travelTimezone
                            ? (travelTimezone === 'Asia/Shanghai' ? 'BEIJING' : travelTimezone.split('/').pop().replace(/_/g, ' ').toUpperCase())
                            : t('timeline.localTimeShort')}
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
            <div className="timeline-scroll-area" onScroll={handleScroll} ref={scrollContainerRef} onMouseDown={handleSelectionMouseDown}>
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
                        journalText={journals?.[format(day, 'yyyy-MM-dd')] || ''}
                        onSaveJournal={(text) => onSaveJournal?.(format(day, 'yyyy-MM-dd'), text)}
                        onContextMenu={onContextMenu}
                        selectedIds={selectedIds}
                    />
                ))}
            </div>

            {/* Rubber-band selection box overlay */}
            {selectionBox && (
                <div style={{
                    position: 'fixed',
                    left: Math.min(selectionBox.x1, selectionBox.x2),
                    top: Math.min(selectionBox.y1, selectionBox.y2),
                    width: Math.abs(selectionBox.x2 - selectionBox.x1),
                    height: Math.abs(selectionBox.y2 - selectionBox.y1),
                    background: 'rgba(201,168,76,0.12)',
                    border: '1px solid rgba(201,168,76,0.7)',
                    zIndex: 9999,
                    pointerEvents: 'none',
                }} />
            )}
        </div>
    );
}
